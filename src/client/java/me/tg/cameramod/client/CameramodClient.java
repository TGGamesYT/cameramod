package me.tg.cameramod.client;

import me.tg.cameramod.CameraServerThing;
import me.tg.cameramod.Cameramod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CameramodClient implements ClientModInitializer {

    public static final Logger LOGGER = LogManager.getLogger(Cameramod.MOD_ID);
    public static final EntityModelLayer MODEL_CAMERA_LAYER = new EntityModelLayer(Identifier.of("camera"), "main");
    public static boolean isCamera = false;
    private static boolean originalF1State = false;
    private static boolean originalPauseState = true;

    @Override
    public void onInitializeClient() {
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

        // Keep cursor unlocked when viewing through a camera
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (isCamera && client.mouse != null) {
                client.mouse.unlockCursor();
            }
        });
    }
}
