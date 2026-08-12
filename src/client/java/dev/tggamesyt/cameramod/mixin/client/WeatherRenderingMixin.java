package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.client.CameraRenderer;
import net.minecraft.client.render.WeatherRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherRendering.class)
public class WeatherRenderingMixin {

    // Skip rain/snow particle + sound spawning during the camera pass — the
    // camera viewpoint would double-trigger them around its own position.
    // (Pre-1.21.9 this was WorldRenderer.addWeatherParticlesAndSound.)
    @Inject(method = "addParticlesAndSound", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipWeatherParticlesAndSoundForCamera(CallbackInfo ci) {
        if (CameraRenderer.isRendering()) ci.cancel();
    }
}
