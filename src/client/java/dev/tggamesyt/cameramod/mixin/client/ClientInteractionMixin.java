package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.CameraEntity;
import dev.tggamesyt.cameramod.ServerItems;
import dev.tggamesyt.cameramod.client.CameraRenderer;
import dev.tggamesyt.cameramod.client.CameramodClient;
import dev.tggamesyt.cameramod.client.gui.EditCameraScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class ClientInteractionMixin {

    // Own cooldown counter — avoids @Shadow dependency on MC's private itemUseCooldown field.
    // Decremented each game tick; set to 4 on each successful camera interaction so the held
    // mouse-button path in handleInputEvents can't re-fire every tick.
    @Unique
    private int cameramod$useCooldown = 0;

    @Inject(method = "tick", at = @At("HEAD"))
    private void cameramod$tickUseCooldown(CallbackInfo ci) {
        if (cameramod$useCooldown > 0) cameramod$useCooldown--;
    }

    /**
     * Intercept right-click ("use item") in camera mode.
     * Always cancels vanilla so the real hotbar item never runs.
     * Only dispatches our camera interaction when the cooldown is 0.
     */
    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void cameramod$interceptUse(CallbackInfo ci) {
        MinecraftClient mc = (MinecraftClient)(Object)this;

        // cameraGuiMode + NOT in cameraMode: right-clicking a camera entity
        // opens its edit GUI instead of running vanilla item use. We don't
        // override cameraMode itself since the tool hotbar still needs its
        // right-click semantics there.
        //
        // Don't open the GUI when the player is holding one of our camera
        // tool items — the tool's own right-click should run instead (e.g.
        // the activator binds the camera, the mover starts moving it).
        // Without this check the GUI opened on every right-click and the
        // tools became unusable while cameraGuiMode was on.
        if (!CameramodClient.cameraMode && CameraRenderer.getCameraGuiMode()
                && mc.player != null && mc.world != null
                && !cameramod$isCameraToolHeld(mc)) {
            if (cameramod$useCooldown == 0) {
                CameraEntity cam = cameramod$raycastNearestCamera(mc);
                if (cam != null) {
                    cameramod$useCooldown = 4;
                    CameramodClient.addTrackedCamera(cam.getUuid());
                    mc.setScreen(new EditCameraScreen(null, cam.getUuid()));
                    ci.cancel();
                    return;
                }
            }
        }

        if (!CameramodClient.cameraMode || mc.player == null || mc.world == null) return;
        int slot = mc.player.getInventory().getSelectedSlot();
        if (slot < 0 || slot >= CameramodClient.CAMERA_HOTBAR_STACKS.length) {
            // Not a camera slot — let vanilla handle it
            return;
        }

        // Always cancel vanilla so the real item never runs.
        ci.cancel();

        if (cameramod$useCooldown > 0) return;
        cameramod$useCooldown = 4;

        // Manual raycast: prefer entity hits (including client-only cameras) over blocks.
        // Reach matches client render distance — a fixed limit feels broken when the
        // player can clearly see (and want to click) a far camera entity.
        double reach = mc.options.getViewDistance().getValue() * 16.0;
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

        // Swing the hand so the player gets visual feedback.
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    @Unique
    private static boolean cameramod$isCameraToolHeld(MinecraftClient mc) {
        if (mc.player == null) return false;
        ItemStack stack = mc.player.getMainHandStack();
        if (stack.isEmpty()) return false;
        Item it = stack.getItem();
        return it == ServerItems.CAMERA_ITEM
            || it == ServerItems.CAMERA_ACTIVATOR
            || it == ServerItems.CAMERA_ORIENTER
            || it == ServerItems.CAMERA_MOVER
            || it == ServerItems.CAMERA_FIXER
            || it == ServerItems.CAMERA_ZOOMER
            || it == ServerItems.CAMERA_GRAVITY
            || it == ServerItems.CAMERA_ATTACHER
            || it == ServerItems.CAMERA_REMOVER;
    }

    /**
     * Raycast for the nearest CameraEntity within view distance. Returns null
     * if a non-camera entity or block is in the way (so we never open the GUI
     * for a camera the player isn't actually looking at).
     */
    @Unique
    private static CameraEntity cameramod$raycastNearestCamera(MinecraftClient mc) {
        double reach = mc.options.getViewDistance().getValue() * 16.0;
        Vec3d eye  = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0F);
        Vec3d end  = eye.add(look.x * reach, look.y * reach, look.z * reach);

        BlockHitResult br = mc.world.raycast(new RaycastContext(
                eye, end,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                mc.player));
        double bestDistSq = (br != null && br.getType() != HitResult.Type.MISS)
                ? eye.squaredDistanceTo(br.getPos())
                : reach * reach;

        CameraEntity hitCam = null;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof CameraEntity cam)) continue;
            Box box = cam.getBoundingBox().expand(cam.getTargetingMargin());
            var opt = box.raycast(eye, end);
            if (opt.isPresent()) {
                double d = eye.squaredDistanceTo(opt.get());
                if (d < bestDistSq) { bestDistSq = d; hitCam = cam; }
            }
        }
        for (CameraEntity cam : CameramodClient.CLIENT_CAMERAS.values()) {
            Box box = cam.getBoundingBox().expand(cam.getTargetingMargin());
            var opt = box.raycast(eye, end);
            if (opt.isPresent()) {
                double d = eye.squaredDistanceTo(opt.get());
                if (d < bestDistSq) { bestDistSq = d; hitCam = cam; }
            }
        }
        return hitCam;
    }
}
