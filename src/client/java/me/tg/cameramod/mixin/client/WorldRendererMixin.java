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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

    // Skip terrain setup entirely during camera render pass.
    // Any modification to terrain state (even just frustum application) causes
    // flickering for the player because the async occlusion pipeline and chunk
    // render lists get corrupted. The camera reuses the player's chunk list.
    @Inject(method = "setupTerrain", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipTerrainSetupForCamera(Camera camera, Frustum frustum, boolean hasForcedFrustum, boolean spectator, CallbackInfo ci) {
        if (CameraRenderer.isRendering()) {
            ci.cancel();
        }
    }

    // Skip sky rendering during camera pass to prevent sky state from leaking
    // into the player's view (causes jitter during sunrise/sunset transitions)
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipSkyForCamera(CallbackInfo ci) {
        if (CameraRenderer.isRendering()) {
            ci.cancel();
        }
    }

    // Skip weather rendering during camera pass (same reason)
    @Inject(method = "renderWeather", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipWeatherForCamera(CallbackInfo ci) {
        if (CameraRenderer.isRendering()) {
            ci.cancel();
        }
    }
}
