package me.tg.cameramod.client;

import me.tg.cameramod.Cameramod;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import me.tg.cameramod.CameraEntity;
import me.tg.cameramod.client.CameraEntityModel;

public class CameraEntityRenderer extends LivingEntityRenderer<CameraEntity, LivingEntityRenderState, EntityModel<? super LivingEntityRenderState>> {

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
