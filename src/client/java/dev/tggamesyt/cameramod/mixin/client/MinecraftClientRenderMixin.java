package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.client.CameraRenderer;
import dev.tggamesyt.cameramod.client.VivecraftCompat;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VR-only camera pass trigger.
 *
 * <p>In flat (non-VR) play the camera pass runs from {@code GameRenderer.render}
 * HEAD (see {@link GameRendererMixin}). But Vivecraft drives
 * {@code GameRenderer.render} once <em>per eye</em>, so running our second
 * {@code renderWorld} from there lands inside an in-progress eye and corrupts the
 * shared {@link net.minecraft.client.render.Camera}, projection matrix and
 * framebuffer that eye depends on — the "on-camera GUI panels resize every frame"
 * breakage.
 *
 * <h2>Why HEAD, not RETURN</h2>
 * {@code MinecraftClient.render(boolean)} is the enclosing once-per-frame method
 * that contains Vivecraft's entire per-eye loop. We must run the camera pass
 * <em>before</em> that loop, not after it.
 *
 * <p>The flat-mode hook works because it runs the camera pass at
 * {@code GameRenderer.render} HEAD and then the <em>real</em> view render runs
 * afterward and overwrites every piece of shared state the camera pass touched —
 * the {@link net.minecraft.client.render.Camera} pose, projection, framebuffer,
 * lightmap, frustum, etc. That "something real renders after us and resets the
 * shared state" property is load-bearing.
 *
 * <p>Running the pass at RETURN (after both eyes) breaks it: our camera pass calls
 * {@code renderWorld}, which calls {@code Camera.update(...)} and leaves the global
 * Camera holding the <em>camera entity's</em> pose as the final state of the frame.
 * Nothing runs afterward to put it back, so Vivecraft's next frame inherits the
 * stale pose and the headset view snaps back whenever the player turns — the
 * reported "it resets my view when I turn around" bug.
 *
 * <p>Injecting at HEAD restores the invariant: we render the camera viewpoint into
 * our offscreen FBO first (dropping Vivecraft into a flat mono pass via
 * {@code VivecraftCompat.beginMonoRender}), then Vivecraft's eye loop runs and
 * re-derives the Camera/projection/framebuffer from the HMD pose, discarding
 * whatever our pass left behind. No-op without Vivecraft / when VR isn't running.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientRenderMixin {

    @Inject(method = "render(Z)V", at = @At("HEAD"))
    private void cameramod$renderVrCameraPass(boolean tick, CallbackInfo ci) {
        if (!VivecraftCompat.isVrActive()) return;
        MinecraftClient mc = (MinecraftClient) (Object) this;
        CameraRenderer.onFrameRendered(mc.gameRenderer, mc.getRenderTickCounter());
    }
}
