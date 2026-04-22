package me.tg.cameramod.mixin.client;

import me.tg.cameramod.CameraEntity;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancel client-side position+rotation lerp for CameraEntity.
 *
 * Server broadcasts EntityPositionSyncS2C every tick (from requestTeleport in
 * server tick). Vanilla client smooths these via lerpPosAndRotation across
 * several ticks. For our cameras, the client owns the visual position/rotation
 * (set every frame in WorldRenderEvents.START), so the lerp just fights us and
 * produces visible jitter on the cam entity model. Skip it entirely.
 */
@Mixin(Entity.class)
public class CameraEntityLerpMixin {

    @Inject(method = "lerpPosAndRotation", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipLerpForCamera(int step, double x, double y, double z, double yaw, double pitch, CallbackInfo ci) {
        if (((Object) this) instanceof CameraEntity) {
            ci.cancel();
        }
    }
}
