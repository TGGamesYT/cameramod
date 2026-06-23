package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.CameraEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Take full client ownership of a CameraEntity's visual rotation (and, where
 * the client drives it, position) by suppressing every server-driven sync path.
 *
 * Server broadcasts EntityPositionSyncS2C every tick (from requestTeleport in
 * server tick). Vanilla smooths these toward the broadcast values. For our
 * cameras the client sets position/rotation every frame in WorldRenderEvents
 * .START (fixer / attachment / edit-screen), so any server smoothing fights us.
 *
 * Three paths must be blocked:
 *  - lerpPosAndRotation:        legacy multi-step lerp.
 *  - updateTrackedHeadRotation: EntityHeadYawS2C, lerps headYaw back.
 *  - updateTrackedPositionAndAngles: the 1.21 path. It calls
 *      interpolator.refreshPositionAndAngles + setPosition + setRotation, i.e.
 *      it DIRECTLY assigns the server rotation AND arms the interpolator to keep
 *      driving it each tick. This is the one the older mixin missed. Symptom:
 *      a fixed-to-player camera's streamed view rapidly oscillates — every 20Hz
 *      tick this yanks cam.yaw to the stale server value, then getLerpedYaw()
 *      sweeps the rendered angle from the fixer's heading toward it across the
 *      frame, and the next fixer run pulls it back. We re-apply ONLY the server
 *      position (so server-moved/static cameras stay put) and never the rotation
 *      or the interpolator, leaving the per-frame fixer as the sole authority.
 */
@Mixin(Entity.class)
public class CameraEntityLerpMixin {

    @Inject(method = "lerpPosAndRotation", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipLerpForCamera(int step, double x, double y, double z, double yaw, double pitch, CallbackInfo ci) {
        if (((Object) this) instanceof CameraEntity) {
            ci.cancel();
        }
    }

    @Inject(method = "updateTrackedHeadRotation", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipHeadYawLerpForCamera(float yaw, int step, CallbackInfo ci) {
        if (((Object) this) instanceof CameraEntity) {
            ci.cancel();
        }
    }

    @Inject(method = "updateTrackedPositionAndAngles", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipTrackedSyncForCamera(Vec3d pos, float yaw, float pitch, CallbackInfo ci) {
        if (((Object) this) instanceof CameraEntity cam) {
            // Apply position only — snap to the server's value (movers/attachment
            // overwrite it per-frame; static fixed cameras don't move). Crucially,
            // do NOT setRotation() and do NOT arm the position interpolator, so the
            // server never touches the camera's facing.
            cam.setPosition(pos.x, pos.y, pos.z);
            ci.cancel();
        }
    }
}
