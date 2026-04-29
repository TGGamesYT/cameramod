package me.tg.cameramod.mixin.client;

import me.tg.cameramod.client.CameraRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugRenderer.class)
public class DebugRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipDebugRender(MatrixStack matrices, Frustum frustum, VertexConsumerProvider.Immediate vertexConsumers, double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
        if (CameraRenderer.isRendering()) ci.cancel();
    }

    @Inject(method = "renderLate", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipDebugRenderLate(MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers, double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
        if (CameraRenderer.isRendering()) ci.cancel();
    }
}
