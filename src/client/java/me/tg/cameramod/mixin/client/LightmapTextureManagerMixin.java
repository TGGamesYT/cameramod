package me.tg.cameramod.mixin.client;

import me.tg.cameramod.client.CameraRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightmapTextureManager.class)
public class LightmapTextureManagerMixin {

    // Prevent lightmap from being recalculated during the camera render pass.
    // The camera pass calls renderWorld() which calls lightmapTextureManager.update(),
    // which can change sky brightness/color values and cause glitchy transitions
    // in the player's view (especially noticeable during day/night transitions).
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipLightmapUpdate(float tickProgress, CallbackInfo ci) {
        if (CameraRenderer.isRendering()) {
            ci.cancel();
        }
    }
}
