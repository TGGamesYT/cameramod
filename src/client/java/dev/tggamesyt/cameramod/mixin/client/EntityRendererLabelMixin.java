package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.client.CameraRenderer;
import net.minecraft.client.render.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class EntityRendererLabelMixin {

    // Handler takes only CallbackInfo on purpose: the target's parameter list
    // changed in 1.21.9 (Text/VertexConsumerProvider/light -> command queue +
    // CameraRenderState) and none of them are needed to decide the cancel.
    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"), cancellable = true)
    private void cameramod$suppressNameTags(CallbackInfo ci) {
        if (CameraRenderer.isRendering() && !CameraRenderer.getCameraNameTags()) {
            ci.cancel();
        }
    }
}
