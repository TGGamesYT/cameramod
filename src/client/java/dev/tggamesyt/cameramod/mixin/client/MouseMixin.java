package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.CameraServerThing;
import dev.tggamesyt.cameramod.ServerItems;
import dev.tggamesyt.cameramod.client.CameramodClient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void cameramod$interceptScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.currentScreen != null) return;
        if (vertical == 0) return;

        if (CameramodClient.cameraMode) {
            ItemStack held = mc.player.getMainHandStack();
            if (held.isOf(ServerItems.CAMERA_MOVER)) {
                if (CameramodClient.serverHasMod && CameramodClient.moverActive) {
                    ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 0, (float) vertical));
                    ci.cancel();
                } else if (!CameramodClient.serverHasMod && CameramodClient.clientMoverActive) {
                    CameramodClient.onClientMoverScroll((float) vertical);
                    ci.cancel();
                }
                // Mover not active: let vanilla scroll the hotbar slot
                return;
            }
            if (held.isOf(ServerItems.CAMERA_ZOOMER) && mc.player.isSneaking()) {
                if (CameramodClient.serverHasMod && CameramodClient.zoomerActive) {
                    ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 1, (float) vertical));
                } else if (!CameramodClient.serverHasMod) {
                    CameramodClient.onClientZoomerScroll((float) vertical);
                }
                ci.cancel();
                return;
            }
            // All other slots: allow vanilla hotbar scrolling
            return;
        }

        // Normal (non-camera-mode) behavior
        ItemStack held = mc.player.getMainHandStack();

        if (held.isOf(ServerItems.CAMERA_MOVER) && CameramodClient.moverActive) {
            ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 0, (float) vertical));
            ci.cancel();
            return;
        }

        if (held.isOf(ServerItems.CAMERA_ZOOMER) && mc.player.isSneaking() && CameramodClient.zoomerActive) {
            ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 1, (float) vertical));
            ci.cancel();
        }
    }
}
