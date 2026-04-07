package me.tg.cameramod.mixin.client;

import me.tg.cameramod.client.CameraRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void cameramod$afterRender(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        CameraRenderer.onFrameRendered((GameRenderer) (Object) this, tickCounter);
    }

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipRenderHand(float tickProgress, boolean sleeping, Matrix4f positionMatrix, CallbackInfo ci) {
        if (CameraRenderer.isRendering()) ci.cancel();
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipBobView(MatrixStack matrices, float tickProgress, CallbackInfo ci) {
        if (CameraRenderer.isRendering()) ci.cancel();
    }

    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipTiltViewWhenHurt(MatrixStack matrices, float tickProgress, CallbackInfo ci) {
        if (CameraRenderer.isRendering()) ci.cancel();
    }

    @Inject(method = "updateCrosshairTarget", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipUpdateCrosshairTarget(float tickProgress, CallbackInfo ci) {
        if (CameraRenderer.isRendering()) ci.cancel();
    }

    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    private void cameramod$fixedCameraFov(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (CameraRenderer.isRendering()) {
            cir.setReturnValue((float) MinecraftClient.getInstance().options.getFov().getValue().intValue());
        }
    }

    // Suppress block outline (hitbox highlight) on camera
    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipBlockOutline(CallbackInfoReturnable<Boolean> cir) {
        if (CameraRenderer.isRendering()) {
            cir.setReturnValue(false);
        }
    }
}
