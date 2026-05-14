package dev.tggamesyt.cameramod.client;

import dev.tggamesyt.cameramod.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class CameramodClient implements ClientModInitializer {

    public static final Logger LOGGER = LogManager.getLogger(Cameramod.MOD_ID);
    public static final EntityModelLayer MODEL_CAMERA_LAYER = new EntityModelLayer(Identifier.of("camera"), "main");
    public static boolean isCamera = false;
    private static boolean originalF1State = false;
    private static boolean originalPauseState = true;

    // Camera mode: F9 keybind toggles the virtual camera-tool hotbar
    public static boolean cameraMode = false;
    private static KeyBinding cameraModeKey;

    // True when the current server has the cameramod plugin loaded
    public static boolean serverHasMod = false;

    // Client-only camera entities (no server mod required)
    // UUID → CameraEntity; managed here, not in the Minecraft world entity list
    public static final java.util.Map<java.util.UUID, CameraEntity> CLIENT_CAMERAS = new java.util.LinkedHashMap<>();

    // Client-mode fixer/attacher two-step selection state
    private static java.util.UUID clientSelectedCamera = null;
    // Client-mode mover state
    private static java.util.UUID clientMoverCamUuid = null;
    private static double clientMoverDistance = 5.0;
    public static boolean clientMoverActive = false;
    // Client-mode zoomer state
    private static java.util.UUID clientZoomerCamUuid = null;

    // Virtual hotbar items shown when camera mode is active (slots 0-8)
    public static final ItemStack[] CAMERA_HOTBAR_STACKS = new ItemStack[] {
        new ItemStack(ServerItems.CAMERA_ITEM),
        new ItemStack(ServerItems.CAMERA_ACTIVATOR),
        new ItemStack(ServerItems.CAMERA_ORIENTER),
        new ItemStack(ServerItems.CAMERA_MOVER),
        new ItemStack(ServerItems.CAMERA_FIXER),
        new ItemStack(ServerItems.CAMERA_ZOOMER),
        new ItemStack(ServerItems.CAMERA_GRAVITY),
        new ItemStack(ServerItems.CAMERA_ATTACHER),
        new ItemStack(ServerItems.CAMERA_REMOVER),
    };

    // Proxy-transfer camera restore: populated on DISCONNECT, consumed on next JOIN tick
    private static java.util.List<PendingCamera> pendingCameraRestore = new java.util.ArrayList<>();
    private static boolean restoreCamerasPending = false;

    private record PendingCamera(
        double x, double y, double z,
        float yaw, float pitch,
        java.util.UUID boundUuid,
        boolean streaming
    ) {}

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

    // Previous frame's fixed-target UUID per camera, to detect when the fixer is removed.
    // On the frame where it transitions non-null → null we send the current yaw/pitch to
    // the server so it persists across unload/reload.
    private static final java.util.HashMap<java.util.UUID, java.util.UUID> lastFixedTarget = new java.util.HashMap<>();

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

        // Start the MJPEG stream server (localhost:7236)
        CameraStreamServer.start();

        // Camera mode keybind (F9)
        cameraModeKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.cameramod.camera_mode", GLFW.GLFW_KEY_F9, "key.categories.gameplay")
        );

        loadClientConfig();

        // Detect whether the server has the mod on join/leave
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            serverHasMod = ClientPlayNetworking.canSend(CameraServerThing.CameraScrollC2SPayload.ID);
            if (!pendingCameraRestore.isEmpty()) {
                restoreCamerasPending = true;
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // Save player-attached client cameras for proxy-transfer restore.
            // Preserve any still-pending cameras from a previous disconnect that hasn't
            // restored yet (rapid proxy hops).
            java.util.List<PendingCamera> nextPending = new java.util.ArrayList<>();
            if (restoreCamerasPending) nextPending.addAll(pendingCameraRestore);
            if (client.player != null) {
                java.util.UUID playerUuid = client.player.getUuid();
                java.util.UUID currentBound = CameraRenderer.getBoundCameraUuid();
                boolean currentStreaming = CameraRenderer.isStreamingEnabled();
                for (CameraEntity cam : CLIENT_CAMERAS.values()) {
                    if (playerUuid.equals(cam.getAttachTargetUuid())) {
                        // Save position RELATIVE to player so it restores correctly on a
                        // different server where the player spawns at different coordinates.
                        double relX = cam.getX() - client.player.getX();
                        double relY = cam.getY() - client.player.getY();
                        double relZ = cam.getZ() - client.player.getZ();
                        nextPending.add(new PendingCamera(
                            relX, relY, relZ,
                            cam.getYaw(), cam.getPitch(),
                            cam.getUuid().equals(currentBound) ? cam.getUuid() : null,
                            currentStreaming && cam.getUuid().equals(currentBound)
                        ));
                    }
                }
            }
            pendingCameraRestore.clear();
            pendingCameraRestore.addAll(nextPending);
            serverHasMod = false;
            cameraMode = false;
            CLIENT_CAMERAS.clear();
            clientSelectedCamera = null;
            clientMoverActive = false;
            clientMoverCamUuid = null;
            clientZoomerCamUuid = null;
        });

        // Ensure SoftCam camera is cleaned up on game exit so it can be recreated next launch
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (Cameramod.softcamCamera != null) {
                SoftCam.deleteCamera(Cameramod.softcamCamera);
                Cameramod.softcamCamera = null;
            }
        }));

        EntityRendererRegistry.register(Cameramod.CAMERA_ENTITY_ENTITY_TYPE, CameraEntityRenderer::new);
        EntityModelLayerRegistry.registerModelLayer(MODEL_CAMERA_LAYER, CameraEntityModel::getTexturedModelData);

        // Right-click interception is handled by ClientInteractionMixin
        // (mixes into MinecraftClient.doItemUse to fully cancel vanilla use).

        // Client-side gamerule overrides: /cm flip|seeschat <true|false>
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                ClientCommandManager.literal("cm")
                    .then(ClientCommandManager.literal("flip")
                        .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(ctx -> {
                                boolean val = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value");
                                CameraRenderer.setLocalFlipped(val);
                                saveClientConfig();
                                ctx.getSource().sendFeedback(net.minecraft.text.Text.literal("cameraFlipped set to " + val + " (local override)"));
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("seeschat")
                        .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(ctx -> {
                                boolean val = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value");
                                CameraRenderer.setLocalSeesChat(val);
                                saveClientConfig();
                                ctx.getSource().sendFeedback(net.minecraft.text.Text.literal("cameraSeesChat set to " + val + " (local override)"));
                                return 1;
                            })))
            );
        });

        // Drop per-camera client state when the entity unloads (player death,
        // moving out of tracking range, etc.). The attacher's delta-yaw path
        // would otherwise apply the entire rotation the player accumulated
        // while the camera was gone, snapping the cam to a wrong heading on
        // reload. Re-logging didn't reproduce because the world tick is
        // stopped at the title screen, so no fresh delta is computed.
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof CameraEntity) {
                java.util.UUID id = entity.getUuid();
                CLIENT_CAMERAS.remove(id);
                smoothedTargetHeight.remove(id);
                lastAttachEntityYaw.remove(id);
                rawYawHistory.remove(id);
                filteredAttachYaw.remove(id);
                cachedRelRotation.remove(id);
                lastFixedTarget.remove(id);
            }
        });

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
            // Build combined list: world entities first, then any client-only cameras not yet in world
            java.util.List<CameraEntity> allCameras = new java.util.ArrayList<>();
            java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof CameraEntity cam && seen.add(cam.getUuid())) allCameras.add(cam);
            }
            for (CameraEntity cam : CLIENT_CAMERAS.values()) {
                if (seen.add(cam.getUuid())) allCameras.add(cam);
            }

            for (CameraEntity cam : allCameras) {
                java.util.UUID attachUuid = cam.getAttachTargetUuid();
                if (attachUuid == null) continue;
                Entity attachTarget = null;
                // Local player isn't always returned by world.getEntities() iteration on the
                // client — check it explicitly so client-side attached cameras follow the
                // player on a freshly-joined server (e.g. proxy transfer).
                if (mc.player != null && mc.player.getUuid().equals(attachUuid)) {
                    attachTarget = mc.player;
                } else {
                    for (Entity e : mc.world.getEntities()) {
                        if (e.getUuid().equals(attachUuid)) { attachTarget = e; break; }
                    }
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

            for (CameraEntity cam : allCameras) {

                // --- Fixer rotation ---
                java.util.UUID camId = cam.getUuid();
                java.util.UUID targetUuid = cam.getFixedTargetUuid();

                // Detect fixer removal: when fixed target goes non-null → null,
                // send the current client-side yaw/pitch to the server so it
                // persists across unload/reload (the fixer never synced to server).
                java.util.UUID prevTarget = lastFixedTarget.get(camId);
                lastFixedTarget.put(camId, targetUuid);
                if (prevTarget != null && targetUuid == null) {
                    ClientPlayNetworking.send(new CameraServerThing.CameraOrientC2SPayload(
                            camId, cam.getYaw(), cam.getPitch()));
                }

                if (targetUuid != null) {
                    Entity target = null;
                    if (mc.player != null && mc.player.getUuid().equals(targetUuid)) {
                        target = mc.player;
                    } else {
                        for (Entity e : mc.world.getEntities()) {
                            if (e.getUuid().equals(targetUuid)) { target = e; break; }
                        }
                    }
                    if (target == null) target = CLIENT_CAMERAS.get(targetUuid);
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

        // ENTITY_UNLOAD also clears from CLIENT_CAMERAS
        // (fires if we ever explicitly add client cameras to the world)

        // Keep cursor unlocked when viewing through a camera
        // Clear zoomer target when player releases shift
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Proxy-transfer camera restore: defer until the player has spawned at a real
            // position with chunks loaded around them. Right after JOIN the player is still
            // at default coords and not in a loaded chunk; placing the camera then drops it.
            if (restoreCamerasPending && client.player != null && client.world != null) {
                int px = (int) Math.floor(client.player.getX()) >> 4;
                int pz = (int) Math.floor(client.player.getZ()) >> 4;
                boolean chunkReady = client.world.getChunkManager().getChunk(px, pz) != null;
                if (chunkReady) {
                    restoreCamerasPending = false;
                    java.util.UUID playerId = client.player.getUuid();
                    for (PendingCamera pc : pendingCameraRestore) {
                        // Initial position: at the player. Attachment offset (saved as a relative
                        // delta at disconnect time) takes effect on the next render frame and
                        // moves the cam to the saved relative spot.
                        CameraEntity cam = new CameraEntity(Cameramod.CAMERA_ENTITY_ENTITY_TYPE, client.world);
                        cam.refreshPositionAndAngles(
                            client.player.getX() + pc.x(),
                            client.player.getY() + pc.y(),
                            client.player.getZ() + pc.z(),
                            pc.yaw(), pc.pitch());
                        cam.setClientOnly(true);
                        cam.setAttachTargetUuid(playerId);
                        cam.setAttachOffset(new net.minecraft.util.math.Vec3d(pc.x(), pc.y(), pc.z()));
                        CLIENT_CAMERAS.put(cam.getUuid(), cam);
                        ((net.minecraft.client.world.ClientWorld) client.world).addEntity(cam);
                        if (pc.streaming()) {
                            CameraRenderer.setBoundCamera(cam.getUuid());
                            CameraRenderer.setStreamingEnabled(true);
                        } else if (pc.boundUuid() != null) {
                            CameraRenderer.setBoundCamera(cam.getUuid());
                        }
                    }
                    pendingCameraRestore.clear();
                }
            }

            if (isCamera && client.mouse != null) {
                client.mouse.unlockCursor();
            }
            if (zoomerActive && client.player != null && !client.player.isSneaking()) {
                zoomerActive = false;
                if (serverHasMod) ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 2, 0f));
                else {
                    clientZoomerCamUuid = null;
                    zoomerActive = false;
                }
            }

            // Camera mode keybind toggle
            if (cameraModeKey != null) {
                while (cameraModeKey.wasPressed()) {
                    cameraMode = !cameraMode;
                }
            }

            // Client-side mover: move camera to follow player
            if (clientMoverActive && clientMoverCamUuid != null && client.player != null) {
                CameraEntity cam = CLIENT_CAMERAS.get(clientMoverCamUuid);
                if (cam != null) {
                    net.minecraft.util.math.Vec3d dir = client.player.getRotationVector().normalize();
                    net.minecraft.util.math.Vec3d target = client.player.getPos().add(dir.multiply(clientMoverDistance));
                    cam.setPosition(target.x, target.y, target.z);
                    cam.lastRenderX = target.x;
                    cam.lastRenderY = target.y;
                    cam.lastRenderZ = target.z;
                }
            }
        });
    }

    // ==================== Camera Mode Interaction ====================

    /**
     * Called by Fabric interaction events when the player right-clicks in camera mode.
     * Dispatches to server (if mod present) or client simulation.
     * @param slot  selected hotbar slot (0-7 = camera tool)
     * @param entity clicked entity, or null
     * @param blockHit clicked block, or null
     */
    public static void onCameraItemInteract(int slot, Entity entity, BlockHitResult blockHit) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || slot < 0 || slot >= CAMERA_HOTBAR_STACKS.length) return;
        boolean sneaking = mc.player.isSneaking();

        if (serverHasMod) {
            dispatchToServer(slot, entity, blockHit, sneaking, mc);
        } else {
            simulateClientSide(slot, entity, blockHit, sneaking, mc);
        }
    }

    private static void dispatchToServer(int slot, Entity entity, BlockHitResult blockHit, boolean sneaking, MinecraftClient mc) {
        java.util.UUID nilUuid = CameraServerThing.CameraItemUseC2SPayload.NIL;
        java.util.UUID camUuid = nilUuid;
        java.util.UUID targetUuid = nilUuid;
        byte actionType = 0;
        int bx = 0, by = 0, bz = 0;
        byte face = 0;

        if (entity instanceof CameraEntity cam) {
            camUuid = cam.getUuid();
        }

        switch (slot) {
            case 0 -> { // Camera item — place
                if (blockHit != null) {
                    net.minecraft.util.math.BlockPos pos = blockHit.getBlockPos();
                    bx = pos.getX(); by = pos.getY(); bz = pos.getZ();
                    face = (byte) blockHit.getSide().ordinal();
                    actionType = 0;
                } else return; // can't place in air
            }
            case 1 -> { // Activator
                if (entity instanceof CameraEntity) { actionType = 1; }
                else { actionType = 0; camUuid = nilUuid; }
            }
            case 2 -> { // Orienter
                // camUuid already set from entity; if air-click use bound camera
                if (!(entity instanceof CameraEntity)) camUuid = nilUuid;
            }
            case 3 -> { // Mover
                if (moverActive) { actionType = 1; } // stop
                else if (entity instanceof CameraEntity) { actionType = 0; } // start on clicked cam
                else return;
            }
            case 4 -> { // Fixer
                if (entity instanceof CameraEntity cam) {
                    if (sneaking) {
                        clientSelectedCamera = cam.getUuid();
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera selected — click entity to fix to"), true);
                        return;
                    }
                    actionType = 0; // toggle fix-to-self
                } else if (clientSelectedCamera != null) {
                    camUuid = clientSelectedCamera;
                    targetUuid = entity != null ? entity.getUuid() : nilUuid;
                    actionType = entity != null ? (byte)1 : (byte)3; // fix to target OR clear
                    clientSelectedCamera = null;
                } else {
                    // air-click: toggle fixer mode on bound camera
                    camUuid = nilUuid; actionType = 3;
                }
            }
            case 5 -> { // Zoomer
                if (entity instanceof CameraEntity cam) {
                    if (sneaking) { actionType = 0; } // set zoom target
                    else return;
                } else {
                    actionType = 1; // clear zoom target
                    camUuid = nilUuid;
                }
            }
            case 6 -> { // Gravity
                if (!(entity instanceof CameraEntity)) return;
                actionType = 0;
            }
            case 7 -> { // Attacher
                if (entity instanceof CameraEntity cam) {
                    if (sneaking) {
                        clientSelectedCamera = cam.getUuid();
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera selected — click entity to attach to"), true);
                        return;
                    }
                    actionType = 0; // toggle attach to self
                } else if (clientSelectedCamera != null) {
                    camUuid = clientSelectedCamera;
                    targetUuid = entity != null ? entity.getUuid() : nilUuid;
                    actionType = 1;
                    clientSelectedCamera = null;
                } else if (sneaking) {
                    // air-click shift: toggle attach mode on bound cam
                    camUuid = nilUuid; actionType = 3;
                } else return;
            }
            case 8 -> { // Remover
                if (entity instanceof CameraEntity cam) {
                    camUuid = cam.getUuid();
                } else return;
            }
            default -> { return; }
        }
        ClientPlayNetworking.send(new CameraServerThing.CameraItemUseC2SPayload(
                (byte) slot, actionType, camUuid, targetUuid, bx, by, bz, face));
    }

    private static void simulateClientSide(int slot, Entity entity, BlockHitResult blockHit, boolean sneaking, MinecraftClient mc) {
        switch (slot) {
            case 0 -> { // Camera item — place
                if (blockHit != null) {
                    net.minecraft.util.math.BlockPos spawnPos = blockHit.getBlockPos().offset(blockHit.getSide());
                    CameraEntity cam = new CameraEntity(Cameramod.CAMERA_ENTITY_ENTITY_TYPE, mc.world);
                    cam.refreshPositionAndAngles(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, mc.player.getYaw(), 0);
                    cam.setClientOnly(true);
                    CLIENT_CAMERAS.put(cam.getUuid(), cam);
                    ((net.minecraft.client.world.ClientWorld) mc.world).addEntity(cam);
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Camera placed (client)"), true);
                }
            }
            case 1 -> { // Activator (mirrors server-side CameraActivatorItem.use)
                if (entity instanceof CameraEntity cam && CLIENT_CAMERAS.containsKey(cam.getUuid())) {
                    java.util.UUID bound = CameraRenderer.getBoundCameraUuid();
                    if (cam.getUuid().equals(bound)) {
                        CameraRenderer.clearBoundCamera();
                        CameraRenderer.setStreamingEnabled(false);
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera unbound"), true);
                    } else {
                        CameraRenderer.setBoundCamera(cam.getUuid());
                        CameraRenderer.setStreamingEnabled(true);
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera bound"), true);
                    }
                } else {
                    // Air or non-camera click: toggle streaming. Falls back to player POV when no
                    // camera is bound — same behavior as server-side activator.
                    if (CameraRenderer.isStreamingEnabled()) {
                        CameraRenderer.setStreamingEnabled(false);
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Streaming disabled"), true);
                    } else {
                        CameraRenderer.setStreamingEnabled(true);
                        java.util.UUID bound = CameraRenderer.getBoundCameraUuid();
                        if (bound == null) {
                            mc.player.sendMessage(net.minecraft.text.Text.literal("Streaming enabled (player POV)"), true);
                        } else {
                            mc.player.sendMessage(net.minecraft.text.Text.literal("Streaming enabled"), true);
                        }
                    }
                }
            }
            case 2 -> { // Orienter
                CameraEntity cam = (entity instanceof CameraEntity ce) ? ce : getBoundClientCamera();
                if (cam != null) {
                    cam.setYaw(mc.player.getYaw()); cam.setPitch(mc.player.getPitch());
                    cam.setHeadYaw(mc.player.getYaw()); cam.setBodyYaw(mc.player.getYaw());
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Camera oriented"), true);
                }
            }
            case 3 -> { // Mover
                if (clientMoverActive) {
                    clientMoverActive = false;
                    if (clientMoverCamUuid != null) {
                        CameraEntity cam2 = CLIENT_CAMERAS.get(clientMoverCamUuid);
                        if (cam2 != null) cam2.setBeingMoved(false);
                    }
                    clientMoverCamUuid = null;
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Camera mover stopped"), true);
                } else if (entity instanceof CameraEntity cam3 && CLIENT_CAMERAS.containsKey(cam3.getUuid())) {
                    clientMoverCamUuid = cam3.getUuid();
                    clientMoverDistance = cam3.getPos().distanceTo(mc.player.getPos());
                    clientMoverActive = true;
                    cam3.setBeingMoved(true);
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Camera mover started"), true);
                }
            }
            case 4 -> { // Fixer
                if (entity instanceof CameraEntity cam4 && CLIENT_CAMERAS.containsKey(cam4.getUuid())) {
                    if (sneaking) {
                        clientSelectedCamera = cam4.getUuid();
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera selected — click entity to fix to"), true);
                    } else {
                        java.util.UUID cur = cam4.getFixedTargetUuid();
                        if (mc.player.getUuid().equals(cur)) { cam4.setFixedTargetUuid(null); mc.player.sendMessage(net.minecraft.text.Text.literal("Tracking disabled"), true); }
                        else { cam4.setFixedTargetUuid(mc.player.getUuid()); mc.player.sendMessage(net.minecraft.text.Text.literal("Camera tracks you"), true); }
                    }
                } else if (clientSelectedCamera != null) {
                    CameraEntity cam5 = CLIENT_CAMERAS.get(clientSelectedCamera);
                    clientSelectedCamera = null;
                    if (cam5 != null && entity != null) {
                        cam5.setFixedTargetUuid(entity.getUuid());
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera fixed to " + entity.getName().getString()), true);
                    }
                } else {
                    CameraEntity cam6 = getBoundClientCamera();
                    if (cam6 != null) {
                        byte newMode = (byte) ((cam6.getFixerMode() + 1) % 2);
                        cam6.setFixerMode(newMode);
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Fixer mode: " + (newMode == 0 ? "Look At" : "Look Same Way")), true);
                    }
                }
            }
            case 5 -> { // Zoomer
                if (entity instanceof CameraEntity cam7 && sneaking && CLIENT_CAMERAS.containsKey(cam7.getUuid())) {
                    clientZoomerCamUuid = cam7.getUuid(); zoomerActive = true;
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Zoom target set"), true);
                } else if (clientZoomerCamUuid != null) {
                    clientZoomerCamUuid = null; zoomerActive = false;
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Zoom cleared"), true);
                }
            }
            case 6 -> { // Gravity
                if (entity instanceof CameraEntity cam8 && CLIENT_CAMERAS.containsKey(cam8.getUuid())) {
                    boolean ng = !cam8.isGravityEnabled();
                    cam8.setGravityEnabled(ng);
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Gravity: " + (ng ? "ON" : "OFF")), true);
                }
            }
            case 7 -> { // Attacher
                if (entity instanceof CameraEntity cam9 && CLIENT_CAMERAS.containsKey(cam9.getUuid())) {
                    if (sneaking) {
                        clientSelectedCamera = cam9.getUuid();
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera selected — click entity to attach to"), true);
                    } else {
                        java.util.UUID cur = cam9.getAttachTargetUuid();
                        if (mc.player.getUuid().equals(cur)) { cam9.setAttachTargetUuid(null); mc.player.sendMessage(net.minecraft.text.Text.literal("Camera detached"), true); }
                        else { cam9.setAttachTargetUuid(mc.player.getUuid()); cam9.setAttachOffset(cam9.getPos().subtract(mc.player.getPos())); mc.player.sendMessage(net.minecraft.text.Text.literal("Camera attached to you"), true); }
                    }
                } else if (clientSelectedCamera != null && entity != null) {
                    CameraEntity cam10 = CLIENT_CAMERAS.get(clientSelectedCamera);
                    clientSelectedCamera = null;
                    if (cam10 != null) {
                        cam10.setAttachTargetUuid(entity.getUuid());
                        cam10.setAttachOffset(cam10.getPos().subtract(entity.getPos()));
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera attached to " + entity.getName().getString()), true);
                    }
                } else if (sneaking) {
                    // Shift+air-click: toggle attach mode on bound camera
                    CameraEntity cam11 = getBoundClientCamera();
                    if (cam11 != null) {
                        byte newMode = (byte) ((cam11.getAttachMode() + 1) % 2);
                        cam11.setAttachMode(newMode);
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Attach mode: " + (newMode == 0 ? "Fixed" : "Orbit")), true);
                    }
                }
            }
            case 8 -> { // Remover
                if (entity instanceof CameraEntity cam12 && CLIENT_CAMERAS.containsKey(cam12.getUuid())) {
                    java.util.UUID camId = cam12.getUuid();
                    if (camId.equals(CameraRenderer.getBoundCameraUuid())) {
                        CameraRenderer.clearBoundCamera();
                        CameraRenderer.setStreamingEnabled(false);
                    }
                    CLIENT_CAMERAS.remove(camId);
                    cam12.discard();
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Camera removed"), true);
                }
            }
        }
    }

    /** Returns the client camera bound to the stream, or first available client camera. */
    private static CameraEntity getBoundClientCamera() {
        java.util.UUID bound = CameraRenderer.getBoundCameraUuid();
        if (bound != null) {
            CameraEntity cam = CLIENT_CAMERAS.get(bound);
            if (cam != null) return cam;
        }
        return CLIENT_CAMERAS.isEmpty() ? null : CLIENT_CAMERAS.values().iterator().next();
    }

    /** Client-side mover scroll (no server). */
    public static void onClientMoverScroll(float delta) {
        clientMoverDistance = Math.max(1.0, clientMoverDistance + delta * 0.5);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("Distance: " + String.format("%.1f", clientMoverDistance)), true);
    }

    /** Client-side zoomer scroll (no server). */
    public static void onClientZoomerScroll(float delta) {
        if (clientZoomerCamUuid == null) return;
        CameraEntity cam = CLIENT_CAMERAS.get(clientZoomerCamUuid);
        if (cam == null) return;
        float zoom = cam.getZoomLevel();
        boolean fine = zoom < 1.0f || (zoom == 1.0f && delta < 0);
        float step = fine ? 0.05f : 0.25f;
        zoom = Math.max(0.5f, Math.min(10.0f, zoom + delta * step));
        cam.setZoomLevel(zoom);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("Zoom: " + String.format("%.2f", zoom) + "x"), true);
    }

    private static Path getConfigPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("cameramod-client.properties");
    }

    private static void loadClientConfig() {
        Path path = getConfigPath();
        if (!Files.exists(path)) return;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            String flip = props.getProperty("cameraFlipped");
            String chat = props.getProperty("cameraSeesChat");
            if (flip != null) CameraRenderer.setLocalFlipped(Boolean.parseBoolean(flip));
            if (chat != null) CameraRenderer.setLocalSeesChat(Boolean.parseBoolean(chat));
        } catch (IOException e) {
            LOGGER.warn("Failed to load cameramod-client.properties", e);
        }
    }

    public static void saveClientConfig() {
        Path path = getConfigPath();
        Properties props = new Properties();
        Boolean flip = CameraRenderer.getLocalFlipped();
        Boolean chat = CameraRenderer.getLocalSeesChat();
        if (flip != null) props.setProperty("cameraFlipped", flip.toString());
        if (chat != null) props.setProperty("cameraSeesChat", chat.toString());
        try (OutputStream out = Files.newOutputStream(path)) {
            props.store(out, "cameramod client overrides");
        } catch (IOException e) {
            LOGGER.warn("Failed to save cameramod-client.properties", e);
        }
    }
}
