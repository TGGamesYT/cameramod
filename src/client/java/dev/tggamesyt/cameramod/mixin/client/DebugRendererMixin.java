package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.client.CameraRenderer;
import net.minecraft.client.render.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugRenderer.class)
public class DebugRendererMixin {

    // 1.21.9: render() gained a trailing boolean and renderLate() was removed.
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipDebugRender(CallbackInfo ci) {
        if (CameraRenderer.isRendering()) ci.cancel();
    }
}
