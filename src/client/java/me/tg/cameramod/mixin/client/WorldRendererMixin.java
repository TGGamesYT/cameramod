package me.tg.cameramod.mixin.client;

import me.tg.cameramod.client.CameraRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.client.render.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    // Add local player to camera's entity list (MC filters it out normally)
    @Inject(method = "getEntitiesToRender", at = @At("TAIL"))
    private void cameramod$addPlayerToCameraView(Camera camera, Frustum frustum, List<Entity> output, CallbackInfoReturnable<Boolean> cir) {
        if (CameraRenderer.isRendering()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && !output.contains(mc.player)) {
                output.add(mc.player);
            }
        }
    }

    // Clouds and weather render normally on the camera — no suppression needed
    // because the camera pass runs BEFORE the player render (HEAD of render()),
    // so the player's renderWorld() overwrites all state afterward.
}
