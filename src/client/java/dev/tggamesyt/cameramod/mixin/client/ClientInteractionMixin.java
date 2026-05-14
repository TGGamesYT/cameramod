package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.CameraEntity;
import dev.tggamesyt.cameramod.client.CameramodClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class ClientInteractionMixin {

    // Vanilla's held-key path in handleInputEvents checks itemUseCooldown (NOT attackCooldown).
    // We need to set it ourselves so the held mouse button doesn't re-trigger doItemUse every tick.
    @Shadow
    private int itemUseCooldown;

    /**
     * Intercept right-click ("use item") in camera mode.
     * Always cancels vanilla so the real hotbar item never runs.
     * Only dispatches our camera interaction when itemUseCooldown is 0,
     * mirroring vanilla's first action inside doItemUse.
     */
    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void cameramod$interceptUse(CallbackInfo ci) {
        MinecraftClient mc = (MinecraftClient)(Object)this;
        if (!CameramodClient.cameraMode || mc.player == null || mc.world == null) return;
        int slot = mc.player.getInventory().getSelectedSlot();
        if (slot < 0 || slot >= CameramodClient.CAMERA_HOTBAR_STACKS.length) {
            // Not a camera slot — let vanilla handle it
            return;
        }

        // Always cancel vanilla so the real item never runs.
        ci.cancel();

        // Cooldown check (mirror vanilla's setting at the start of doItemUse).
        if (this.itemUseCooldown > 0) return;
        this.itemUseCooldown = 4;

        // Manual raycast: prefer entity hits (including client-only cameras) over blocks.
        double reach = 5.0;
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0F);
        Vec3d end = eye.add(look.x * reach, look.y * reach, look.z * reach);

        BlockHitResult blockResult = mc.world.raycast(new RaycastContext(
                eye, end,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                mc.player));
        double blockDistSq = (blockResult != null && blockResult.getType() != HitResult.Type.MISS)
                ? eye.squaredDistanceTo(blockResult.getPos())
                : reach * reach;

        Entity hitEntity = null;
        double bestEntDistSq = blockDistSq;
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player || !e.canHit() && !(e instanceof CameraEntity)) continue;
            Box box = e.getBoundingBox().expand(e.getTargetingMargin());
            var opt = box.raycast(eye, end);
            if (opt.isPresent()) {
                double d = eye.squaredDistanceTo(opt.get());
                if (d < bestEntDistSq) { bestEntDistSq = d; hitEntity = e; }
            }
        }
        for (CameraEntity cam : CameramodClient.CLIENT_CAMERAS.values()) {
            Box box = cam.getBoundingBox().expand(cam.getTargetingMargin());
            var opt = box.raycast(eye, end);
            if (opt.isPresent()) {
                double d = eye.squaredDistanceTo(opt.get());
                if (d < bestEntDistSq) { bestEntDistSq = d; hitEntity = cam; }
            }
        }

        Entity entity = hitEntity;
        BlockHitResult blockHit = (hitEntity == null
                && blockResult != null
                && blockResult.getType() != HitResult.Type.MISS)
                ? blockResult : null;

        CameramodClient.onCameraItemInteract(slot, entity, blockHit);

        // Swing the hand so the player gets visual feedback (vanilla doItemUse does this on success).
        mc.player.swingHand(Hand.MAIN_HAND);
    }
}
