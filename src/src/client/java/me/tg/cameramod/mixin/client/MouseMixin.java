package me.tg.cameramod.mixin.client;

import me.tg.cameramod.CameraServerThing;
import me.tg.cameramod.ServerItems;
import me.tg.cameramod.client.CameramodClient;
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

        ItemStack held = mc.player.getMainHandStack();

        // Camera Mover: when active, scroll adjusts follow distance
        if (held.isOf(ServerItems.CAMERA_MOVER) && CameramodClient.moverActive) {
            ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 0, (float) vertical));
            ci.cancel();
            return;
        }

        // Camera Zoomer: while sneaking AND a camera is selected for zooming, scroll adjusts zoom
        if (held.isOf(ServerItems.CAMERA_ZOOMER) && mc.player.isSneaking() && CameramodClient.zoomerActive) {
            ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 1, (float) vertical));
            ci.cancel();
        }
    }
}
