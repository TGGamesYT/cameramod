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

    /**
     * During the camera render pass, the local ClientPlayerEntity is filtered out
     * because MC only renders it when it's the camera's focused entity (for third-person).
     * Since our focused entity is the CameraEntity, the player is excluded.
     * This injects at the end of getEntitiesToRender to add the player back in.
     */
    @Inject(method = "getEntitiesToRender", at = @At("TAIL"))
    private void cameramod$addPlayerToCameraView(Camera camera, Frustum frustum, List<Entity> output, CallbackInfoReturnable<Boolean> cir) {
        if (CameraRenderer.isRendering()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && !output.contains(mc.player)) {
                output.add(mc.player);
            }
        }
    }
}
