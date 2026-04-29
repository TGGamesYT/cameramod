package me.tg.cameramod.mixin.client;

import me.tg.cameramod.client.CameraRenderer;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.render.CloudRenderer;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
public class CloudRendererMixin {

    @Inject(method = "renderClouds(ILnet/minecraft/client/option/CloudRenderMode;FLnet/minecraft/util/math/Vec3d;F)V", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipCloudsForCamera(int color, CloudRenderMode mode, float cloudHeight, Vec3d cameraPos, float cloudPhase, CallbackInfo ci) {
        if (CameraRenderer.isRendering()) {
            ci.cancel();
        }
    }
}
