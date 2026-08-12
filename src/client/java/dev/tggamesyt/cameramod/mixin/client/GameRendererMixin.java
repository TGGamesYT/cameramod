package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.Cameramod;
import dev.tggamesyt.cameramod.client.CameraRenderer;
import dev.tggamesyt.cameramod.client.VivecraftCompat;
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

    // Render camera pass BEFORE the player render so the player's renderWorld()
    // overwrites all state (frustum, fog, lightmap, chunks, etc.), preventing
    // entity jitter and sky/cloud glitches in the player's view.
    //
    // In VR this is the WRONG place: Vivecraft drives GameRenderer.render once per
    // eye, so our second renderWorld would run inside an in-progress eye and
    // corrupt the shared Camera/projection/framebuffer it depends on. There the
    // camera pass instead runs once per frame from MinecraftClientRenderMixin,
    // after Vivecraft's whole eye loop. (isVrActive() is a no-op without Vivecraft.)
    @Inject(method = "render", at = @At("HEAD"))
    private void cameramod$beforeRender(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (VivecraftCompat.isVrActive()) return;
        CameraRenderer.onFrameRendered((GameRenderer) (Object) this, tickCounter);
    }

    // Capture player POV AFTER the frame is fully rendered (for when no camera is bound)
    @Inject(method = "render", at = @At("RETURN"))
    private void cameramod$afterRender(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        CameraRenderer.onFrameFinished();
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
            float baseFov = (float) MinecraftClient.getInstance().options.getFov().getValue().intValue();
            float zoom = CameraRenderer.getActiveZoomLevel();
            if (zoom > 0.0f) baseFov /= zoom;
            cir.setReturnValue(baseFov);
        }
    }

    // Vanilla builds the projection matrix from the window's framebuffer aspect
    // ratio, which makes the camera image stretch/squash whenever the player
    // resizes the Minecraft window. During the camera pass we substitute the
    // camera's own aspect ratio so the captured frame is always undistorted
    // regardless of window dimensions.
    @Inject(method = "getBasicProjectionMatrix", at = @At("HEAD"), cancellable = true)
    private void cameramod$cameraAspectProjection(float fov, CallbackInfoReturnable<Matrix4f> cir) {
        if (CameraRenderer.isRendering()) {
            GameRenderer self = (GameRenderer) (Object) this;
            float aspect = (float) Cameramod.camwidth / (float) Cameramod.camheight;
            Matrix4f m = new Matrix4f().perspective(
                    fov * 0.017453292f,
                    aspect,
                    0.05f,
                    self.getFarPlaneDistance()
            );
            cir.setReturnValue(m);
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
