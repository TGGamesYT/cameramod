package me.tg.cameramod.client;

import me.tg.cameramod.Cameramod;
import me.tg.cameramod.CameraEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.util.Identifier;

public class CameraEntityRenderer extends LivingEntityRenderer<CameraEntity, CameraEntityRenderState, CameraEntityModel> {

    private static final Identifier TEXTURE = Identifier.of(Cameramod.MOD_ID, "textures/entity/camera.png");

    public CameraEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new CameraEntityModel(ctx.getPart(CameramodClient.MODEL_CAMERA_LAYER)), 0.5f);
    }

    @Override
    public CameraEntityRenderState createRenderState() {
        return new CameraEntityRenderState();
    }

    @Override
    public void updateRenderState(CameraEntity entity, CameraEntityRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.zoomLevel = entity.getZoomLevel();
        // Show legs when gravity is enabled AND camera is on a surface.
        // Hide legs when gravity is disabled or floating in air.
        state.showLegs = entity.isGravityEnabled() && entity.hasBlockBelow();
    }

    @Override
    public Identifier getTexture(CameraEntityRenderState state) {
        return TEXTURE;
    }
}
