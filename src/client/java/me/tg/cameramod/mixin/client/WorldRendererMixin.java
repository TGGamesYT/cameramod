package me.tg.cameramod.mixin.client;

import me.tg.cameramod.client.CameraRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.FrameGraphBuilder;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.Vec3d;
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

    // Suppress cloud rendering during camera pass to prevent cloud jitter on player view
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipCloudsOnCamera(FrameGraphBuilder frameGraphBuilder, CloudRenderMode mode, Vec3d cameraPos, float cloudPhase, int color, float cloudHeight, CallbackInfo ci) {
        if (CameraRenderer.isRendering()) {
            ci.cancel();
        }
    }
}
