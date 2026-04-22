package me.tg.cameramod.client;

import me.tg.cameramod.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CameramodClient implements ClientModInitializer {

    public static final Logger LOGGER = LogManager.getLogger(Cameramod.MOD_ID);
    public static final EntityModelLayer MODEL_CAMERA_LAYER = new EntityModelLayer(Identifier.of("camera"), "main");
    public static boolean isCamera = false;
    private static boolean originalF1State = false;
    private static boolean originalPauseState = true;

    // Client-side state flags (set by S2C packets)
    public static boolean moverActive = false;
    public static boolean zoomerActive = false;

    // Smoothed target height for fixer "Look At" mode (lerps during sneak transitions)
    private static final java.util.HashMap<java.util.UUID, Float> smoothedTargetHeight = new java.util.HashMap<>();
    // Previous frame's raw entity yaw per camera, for delta-based attacher rotation
    private static final java.util.HashMap<java.util.UUID, Float> lastAttachEntityYaw = new java.util.HashMap<>();
    // Last 4 raw yaw samples per camera, for median-of-5 spike rejection
    // (median-of-N rejects up to floor((N-1)/2) consecutive outliers, so 5 → 2-tick)
    private static final java.util.HashMap<java.util.UUID, float[]> rawYawHistory = new java.util.HashMap<>();
    // Filtered entity yaw shared from attachment pass to fixer pass (per cam)
    private static final java.util.HashMap<java.util.UUID, Float> filteredAttachYaw = new java.util.HashMap<>();

    // Cached relative cam rotation (cam yaw/pitch in target's local frame), captured
    // ONCE when fixer+head-attach become active for a given offset. Reused every
    // frame so cam rotation is just `targetYaw + cachedRelYaw` — no per-frame
    // look-at recompute, no floating drift, no jitter.
    private static final class CachedRel {
        final float relYaw, pitch;
        final double offX, offY, offZ;
        CachedRel(float relYaw, float pitch, double ox, double oy, double oz) {
            this.relYaw = relYaw; this.pitch = pitch; this.offX = ox; this.offY = oy; this.offZ = oz;
        }
    }
    private static final java.util.HashMap<java.util.UUID, CachedRel> cachedRelRotation = new java.util.HashMap<>();

    @Override
    public void onInitializeClient() {
        // Initialize SoftCam on the client side only (not on dedicated servers)
        SoftCam.initialize();
        Cameramod.softcamCamera = SoftCam.createCamera(Cameramod.camwidth, Cameramod.camheight, Cameramod.camframerate);

        // Ensure SoftCam camera is cleaned up on game exit so it can be recreated next launch
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (Cameramod.softcamCamera != null) {
                SoftCam.deleteCamera(Cameramod.softcamCamera);
                Cameramod.softcamCamera = null;
            }
        }));

        EntityRendererRegistry.register(Cameramod.CAMERA_ENTITY_ENTITY_TYPE, CameraEntityRenderer::new);
        EntityModelLayerRegistry.registerModelLayer(MODEL_CAMERA_LAYER, CameraEntityModel::getTexturedModelData);

        // Manual camera view switching via /setcamera
        ClientPlayNetworking.registerGlobalReceiver(CameraServerThing.SetCameraS2CPayload.ID, (payload, context) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            Mouse mouse = mc.mouse;
            if (isCamera) {
                isCamera = false;
                mc.cameraEntity = context.player();
                mc.gameRenderer.getCamera().reset();
                mc.options.hudHidden = originalF1State;
                mc.options.pauseOnLostFocus = originalPauseState;
            } else {
                isCamera = true;
                mc.cameraEntity = context.player().getWorld().getEntity(payload.uuid());
                mc.gameRenderer.getCamera().reset();
                originalF1State = mc.options.hudHidden;
                originalPauseState = mc.options.pauseOnLostFocus;
                mc.options.pauseOnLostFocus = false;
                mc.options.hudHidden = true;
                mouse.unlockCursor();
            }
        });

        // Camera bind/unbind for virtualcam rendering
        ClientPlayNetworking.registerGlobalReceiver(CameraServerThing.BindCameraS2CPayload.ID, (payload, context) -> {
            CameraRenderer.setBoundCamera(payload.cameraUuid());
        });

        ClientPlayNetworking.registerGlobalReceiver(CameraServerThing.UnbindCameraS2CPayload.ID, (payload, context) -> {
            CameraRenderer.clearBoundCamera();
        });

        // Mover/Zoomer/Streaming state updates
        ClientPlayNetworking.registerGlobalReceiver(CameraServerThing.CameraItemStateS2CPayload.ID, (payload, context) -> {
            if (payload.type() == 0) {
                moverActive = payload.active();
            } else if (payload.type() == 1) {
                zoomerActive = payload.active();
            } else if (payload.type() == 2) {
                CameraRenderer.setStreamingEnabled(payload.active());
            } else if (payload.type() == 3) {
                CameraRenderer.setCameraSeesChat(payload.active());
            } else if (payload.type() == 4) {
                CameraRenderer.setCameraFlipped(payload.active());
            }
        });

        // Per-frame client-side updates for all camera entities:
        // - Fixer rotation (smooth interpolated tracking)
        // - Attachment position (smooth lerp avoids 20tps server lag)
        WorldRenderEvents.START.register(context -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null) return;
            float tickDelta = context.tickCounter().getTickProgress(false);
            // Pass 1: attachment position. Pass 2: fixer rotation.
            // Order matters — fixer reads cam position to compute look-at angle, so
            // the cam must already be at its final frame position when fixer runs,
            // otherwise the angle is one frame stale and visibly jitters.
            for (Entity entity : mc.world.getEntities()) {
                if (!(entity instanceof CameraEntity cam)) continue;
                java.util.UUID attachUuid = cam.getAttachTargetUuid();
                if (attachUuid == null) continue;
                Entity attachTarget = null;
                for (Entity e : mc.world.getEntities()) {
                    if (e.getUuid().equals(attachUuid)) { attachTarget = e; break; }
                }
                if (attachTarget == null) continue;
                net.minecraft.util.math.Vec3d offset = cam.getAttachOffset();
                double ax = net.minecraft.util.math.MathHelper.lerp(tickDelta, attachTarget.lastRenderX, attachTarget.getX());
                double ay = net.minecraft.util.math.MathHelper.lerp(tickDelta, attachTarget.lastRenderY, attachTarget.getY());
                double az = net.minecraft.util.math.MathHelper.lerp(tickDelta, attachTarget.lastRenderZ, attachTarget.getZ());
                double tx, tz;
                if (cam.getAttachMode() == 1) {
                    java.util.UUID camId = cam.getUuid();
                    // Single source of truth for entity yaw this frame. Used for BOTH
                    // offset rotation (this pass) and fixer look-at (next pass).
                    // Local player → input-driven yaw, else network-lerped yaw.
                    float rawYaw = (attachTarget == mc.player)
                            ? net.minecraft.util.math.MathHelper.lerp(tickDelta, mc.player.lastYaw, mc.player.getYaw())
                            : net.minecraft.util.math.MathHelper.lerp(tickDelta, attachTarget.lastYaw, attachTarget.getYaw());

                    // Median-of-5 spike filter: rejects up to 2 consecutive outlier
                    // ticks. Wrap-aware via deltas relative to the most recent stable.
                    float[] hist = rawYawHistory.getOrDefault(camId, new float[]{rawYaw, rawYaw, rawYaw, rawYaw});
                    float ref = hist[3];
                    float[] deltas = new float[]{
                            net.minecraft.util.math.MathHelper.wrapDegrees(hist[0] - ref),
                            net.minecraft.util.math.MathHelper.wrapDegrees(hist[1] - ref),
                            net.minecraft.util.math.MathHelper.wrapDegrees(hist[2] - ref),
                            0f,
                            net.minecraft.util.math.MathHelper.wrapDegrees(rawYaw - ref),
                    };
                    float[] sorted = deltas.clone();
                    java.util.Arrays.sort(sorted);
                    float filteredYaw = ref + sorted[2];
                    hist[0] = hist[1]; hist[1] = hist[2]; hist[2] = hist[3]; hist[3] = rawYaw;
                    rawYawHistory.put(camId, hist);
                    filteredAttachYaw.put(camId, filteredYaw);

                    float prevYaw = lastAttachEntityYaw.getOrDefault(camId, filteredYaw);
                    float dYaw = net.minecraft.util.math.MathHelper.wrapDegrees(filteredYaw - prevYaw);
                    lastAttachEntityYaw.put(camId, filteredYaw);

                    // Position offset orbits with entity yaw (filtered)
                    float yawRad = (float) (filteredYaw * Math.PI / 180.0);
                    tx = ax + offset.x * Math.cos(yawRad) - offset.z * Math.sin(yawRad);
                    tz = az + offset.x * Math.sin(yawRad) + offset.z * Math.cos(yawRad);


                    // Cam facing: rotate from its CURRENT yaw by the entity's delta.
                    if (cam.getFixedTargetUuid() == null) {
                        float newYaw = cam.getYaw() + dYaw;
                        cam.setYaw(newYaw);
                        cam.setHeadYaw(newYaw);
                        cam.setBodyYaw(newYaw);
                        cam.lastYaw = newYaw;
                    }
                } else {
                    tx = ax + offset.x;
                    tz = az + offset.z;
                }
                double ty = ay + offset.y;
                cam.setPosition(tx, ty, tz);
                cam.lastRenderX = tx;
                cam.lastRenderY = ty;
                cam.lastRenderZ = tz;
                cam.lastX = tx;
                cam.lastY = ty;
                cam.lastZ = tz;
            }

            for (Entity entity : mc.world.getEntities()) {
                if (!(entity instanceof CameraEntity cam)) continue;

                // --- Fixer rotation ---
                java.util.UUID targetUuid = cam.getFixedTargetUuid();
                if (targetUuid != null) {
                    Entity target = null;
                    for (Entity e : mc.world.getEntities()) {
                        if (e.getUuid().equals(targetUuid)) { target = e; break; }
                    }
                    if (target != null) {
                        float yaw, pitch;
                        if (cam.getFixerMode() == 1) {
                            // Look Same Way mode: copy target's interpolated rotation
                            yaw = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastYaw, target.getYaw());
                            pitch = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastPitch, target.getPitch());
                        } else {
                            // Look At mode: face toward target
                            // Smooth the height offset to avoid jumps during sneak transitions
                            float desiredHeight = target.isSneaking() ? target.getHeight() * 0.625f : target.getHeight() * 0.725f;
                            java.util.UUID camId = cam.getUuid();
                            float currentSmoothed = smoothedTargetHeight.getOrDefault(camId, desiredHeight);
                            float lerpSpeed = 0.15f;
                            currentSmoothed += (desiredHeight - currentSmoothed) * lerpSpeed;
                            smoothedTargetHeight.put(camId, currentSmoothed);

                            // Fast path: cam fixed-to + head-attached-to SAME target.
                            // Use cached relative rotation. For player target use
                            // CLIENT player's own rotation (input-driven, jitter-free,
                            // not the network-lerped copy that micro-jitters).
                            java.util.UUID attachId = cam.getAttachTargetUuid();
                            if (attachId != null && attachId.equals(targetUuid) && cam.getAttachMode() == 1) {
                                net.minecraft.util.math.Vec3d off = cam.getAttachOffset();
                                CachedRel rel = cachedRelRotation.get(camId);
                                if (rel == null
                                        || rel.offX != off.x || rel.offY != off.y || rel.offZ != off.z) {
                                    // (Re)capture once: relative look-at angle in target-local frame
                                    double localYaw = Math.atan2(-off.z, -off.x) * (180.0 / Math.PI) - 90.0;
                                    double horiz = Math.sqrt(off.x * off.x + off.z * off.z);
                                    double dyRel = currentSmoothed - off.y - cam.getStandingEyeHeight();
                                    float pitchCap = (float) (-(Math.atan2(dyRel, horiz) * (180.0 / Math.PI)));
                                    rel = new CachedRel((float) localYaw, pitchCap, off.x, off.y, off.z);
                                    cachedRelRotation.put(camId, rel);
                                }
                                // Read SAME yaw the attachment pass used for offset rotation
                                Float sharedYaw = filteredAttachYaw.get(camId);
                                float entYaw = (sharedYaw != null) ? sharedYaw
                                        : net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastYaw, target.getYaw());
                                yaw = entYaw + rel.relYaw;
                                pitch = rel.pitch;
                            } else {
                                cachedRelRotation.remove(camId);
                                double tx = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastRenderX, target.getX());
                                double ty = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastRenderY, target.getY())
                                        + currentSmoothed;
                                double tz = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastRenderZ, target.getZ());
                                double cx = net.minecraft.util.math.MathHelper.lerp(tickDelta, cam.lastRenderX, cam.getX());
                                double cy = net.minecraft.util.math.MathHelper.lerp(tickDelta, cam.lastRenderY, cam.getY())
                                        + cam.getStandingEyeHeight();
                                double cz = net.minecraft.util.math.MathHelper.lerp(tickDelta, cam.lastRenderZ, cam.getZ());

                                double dx = tx - cx;
                                double dy = ty - cy;
                                double dz = tz - cz;
                                double horizontal = Math.sqrt(dx * dx + dz * dz);

                                yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
                                pitch = (float) (-(Math.atan2(dy, horizontal) * (180.0 / Math.PI)));
                            }
                        }

                        cam.setYaw(yaw);
                        cam.setPitch(pitch);
                        cam.setHeadYaw(yaw);
                        cam.setBodyYaw(yaw);
                        cam.lastYaw = yaw;
                        cam.lastPitch = pitch;
                    }
                }

            }
        });

        // Keep cursor unlocked when viewing through a camera
        // Clear zoomer target when player releases shift
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (isCamera && client.mouse != null) {
                client.mouse.unlockCursor();
            }
            if (zoomerActive && client.player != null && !client.player.isSneaking()) {
                zoomerActive = false;
                ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 2, 0f));
            }
        });
    }
}
