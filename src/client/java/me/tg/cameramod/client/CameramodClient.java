package me.tg.cameramod.client;

import me.tg.cameramod.CameraServerThing;
import me.tg.cameramod.Cameramod;
import me.tg.cameramod.SoftCam;
import net.fabricmc.api.ClientModInitializer;
import me.tg.cameramod.CameraEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
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

        // Smooth camera fixer tracking: compute rotation client-side every frame
        // using interpolated entity positions for F5-like smoothness
        WorldRenderEvents.START.register(context -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null) return;
            float tickDelta = context.tickCounter().getTickProgress(false);
            for (Entity entity : mc.world.getEntities()) {
                if (!(entity instanceof CameraEntity cam)) continue;
                java.util.UUID targetUuid = cam.getFixedTargetUuid();
                if (targetUuid == null) continue;
                Entity target = null;
                for (Entity e : mc.world.getEntities()) {
                    if (e.getUuid().equals(targetUuid)) { target = e; break; }
                }
                if (target == null) continue;

                float yaw, pitch;
                if (cam.getFixerMode() == 1) {
                    // Look Same Way mode: copy target's interpolated rotation
                    yaw = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastYaw, target.getYaw());
                    pitch = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastPitch, target.getPitch());
                } else {
                    // Look At mode: face toward target
                    double tx = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastRenderX, target.getX());
                    double ty = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastRenderY, target.getY())
                            + target.getStandingEyeHeight() - 0.15;
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

                cam.setYaw(yaw);
                cam.setPitch(pitch);
                cam.setHeadYaw(yaw);
                cam.setBodyYaw(yaw);
                cam.lastYaw = yaw;
                cam.lastPitch = pitch;
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
