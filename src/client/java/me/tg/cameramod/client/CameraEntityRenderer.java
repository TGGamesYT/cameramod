package me.tg.cameramod.client;

import me.tg.cameramod.Cameramod;
import me.tg.cameramod.CameraEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.util.Identifier;

public class CameraEntityRenderer extends LivingEntityRenderer<CameraEntity, LivingEntityRenderState, CameraEntityModel> {

    private static final Identifier TEXTURE = Identifier.of(Cameramod.MOD_ID, "textures/entity/camera.png");

    public CameraEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new CameraEntityModel(ctx.getPart(CameramodClient.MODEL_CAMERA_LAYER)), 0.5f);
    }

    @Override
    public LivingEntityRenderState createRenderState() {
        return new LivingEntityRenderState();
    }

    @Override
    public Identifier getTexture(LivingEntityRenderState state) {
        return TEXTURE;
    }
}
