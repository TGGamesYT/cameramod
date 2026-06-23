package dev.tggamesyt.cameramod.client;

import dev.tggamesyt.cameramod.Cameramod;
import dev.tggamesyt.cameramod.CameraEntity;
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

    /**
     * Never let a camera entity be culled. canBeCulled() is the vanilla
     * EntityRenderer hook that well-behaved culling mods (e.g. occlusion-based
     * entity culling) query before skipping an entity's render. When a camera
     * is fixed-to / attached-to the player and the player looks away, an
     * occlusion culler would normally cull the camera entity — but the camera's
     * client-side fixer/attachment runs in the render path, and culling it
     * stops its visual state from updating, so the streamed view jitters/breaks
     * until the player looks back. Returning false marks the camera as
     * unconditionally render-relevant, which culling mods honor generically
     * (no mod-specific code) — they cannot re-enable culling for it.
     */
    @Override
    protected boolean canBeCulled(CameraEntity entity) {
        return false;
    }
}
