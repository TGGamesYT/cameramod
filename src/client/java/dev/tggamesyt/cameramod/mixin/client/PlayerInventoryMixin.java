package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.client.CameramodClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerInventory.class)
public class PlayerInventoryMixin {

    @Inject(method = "getStack", at = @At("HEAD"), cancellable = true)
    private void cameramod$virtualHotbar(int slot, CallbackInfoReturnable<ItemStack> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.currentScreen != null) return;
        if (CameramodClient.cameraMode && slot >= 0 && slot < CameramodClient.CAMERA_HOTBAR_STACKS.length) {
            cir.setReturnValue(CameramodClient.CAMERA_HOTBAR_STACKS[slot]);
        }
    }

    // getSelectedStack() reads the main hand item directly (bypasses getStack), so we need this too
    @Inject(method = "getSelectedStack", at = @At("HEAD"), cancellable = true)
    private void cameramod$virtualSelectedStack(CallbackInfoReturnable<ItemStack> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.currentScreen != null) return;
        if (CameramodClient.cameraMode) {
            int slot = ((PlayerInventory)(Object)this).getSelectedSlot();
            if (slot >= 0 && slot < CameramodClient.CAMERA_HOTBAR_STACKS.length) {
                cir.setReturnValue(CameramodClient.CAMERA_HOTBAR_STACKS[slot]);
            }
        }
    }

    /**
     * Prevent any camera-tool ItemStack from being written to a real hotbar slot
     * while camera mode is on. This catches the leak that happens during screen
     * init/sync where the slot handler may copy our virtual stack and write the
     * copy back via setStack(). Reference equality wasn't enough because copies
     * are different objects — we check the item identity instead.
     */
    @Inject(method = "setStack", at = @At("HEAD"), cancellable = true)
    private void cameramod$blockVirtualWrite(int slot, ItemStack stack, CallbackInfo ci) {
        if (!CameramodClient.cameraMode) return;
        if (slot < 0 || slot >= CameramodClient.CAMERA_HOTBAR_STACKS.length) return;
        if (stack == null || stack.isEmpty()) return;
        for (ItemStack cs : CameramodClient.CAMERA_HOTBAR_STACKS) {
            if (stack.isOf(cs.getItem())) { ci.cancel(); return; }
        }
    }
}
