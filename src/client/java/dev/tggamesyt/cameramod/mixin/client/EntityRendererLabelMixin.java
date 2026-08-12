package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.client.CameraRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class EntityRendererLabelMixin {

    // Camera pass: hide ALL entity name tags in the stream unless the camera
    // name-tags setting is on. (Hiding camera entities' OWN tags in the player's
    // normal view is done in CameraEntityRenderer.updateRenderState instead, so it
    // stays portable across versions whose renderLabelIfPresent signature differs.)
    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"), cancellable = true)
    private void cameramod$suppressNameTags(EntityRenderState state, Text text,
            MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if (CameraRenderer.isRendering() && !CameraRenderer.getCameraNameTags()) {
            ci.cancel();
        }
    }
}
