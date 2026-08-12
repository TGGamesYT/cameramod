package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.client.CameraRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Points {@code renderWorld} at the camera entity during the VR camera pass
 * WITHOUT writing {@code MinecraftClient.cameraEntity}.
 *
 * <p>{@code setCameraEntity()} calls {@code GameRenderer.onCameraEntitySet()},
 * which Vivecraft hooks to re-origin its VR head tracking. The camera pass swaps
 * the camera entity in and back out every frame, so in VR that fired the re-origin
 * twice per frame and the headset view snapped back whenever the player turned to
 * the side. Instead the pass sets {@link CameraRenderer#getCameraEntityOverride()}
 * and this mixin makes {@code getCameraEntity()} return it for the pass's duration,
 * leaving the real field untouched so Vivecraft never sees a camera-entity change.
 *
 * <p>The override is only set during the VR camera pass; at all other times it is
 * {@code null} and this is a no-op (flat play still uses the plain field swap).
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientCameraEntityMixin {

    @Inject(method = "getCameraEntity", at = @At("HEAD"), cancellable = true)
    private void cameramod$overrideCameraEntity(CallbackInfoReturnable<Entity> cir) {
        Entity override = CameraRenderer.getCameraEntityOverride();
        if (override != null) cir.setReturnValue(override);
    }
}
