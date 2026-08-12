package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.client.CameraRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
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

    // ─── Per-frame camera pipeline (ex-WorldRenderEvents.START) ──────────────
    // Fabric removed WorldRenderEvents in the 1.21.9 world-render refactor.
    // Fire the attachment/fixer pipeline from the same spot the old event
    // fired: the head of WorldRenderer.render. The handler itself ignores
    // re-entrant calls from our camera pass (CameraRenderer.isRendering()).
    @Inject(method = "render", at = @At("HEAD"))
    private void cameramod$onRenderStart(CallbackInfo ci) {
        dev.tggamesyt.cameramod.client.CameramodClient.onWorldRenderStart();
    }

    // ─── Skip expensive per-frame effects during camera pass ─────────────────
    // Weather (rain/snow) is visually minor for the stream output and adds
    // non-trivial GPU cost each camera frame, so it stays skipped. Particles,
    // however, ARE drawn in the camera POV (users expect them) — the old
    // renderParticles skip was removed for that reason.

    @Inject(method = "renderWeather", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipWeatherForCamera(CallbackInfo ci) {
        if (CameraRenderer.isRendering()) ci.cancel();
    }

    // ─── Translucency sort during camera pass (vanilla water/glass) ───────────
    // Vanilla keeps ONE async-sorted translucent index buffer per chunk, shared
    // by our camera pass and the player pass. A single buffer cannot be correctly
    // ordered for two different viewpoints at once: re-sorting it every frame for
    // each view makes it oscillate (visible water flicker, worst at ~90° between
    // the two views). So we skip the sort during the camera pass and let the
    // player's order stand — the camera's water uses the player's sort, which is
    // stable. (A flicker-free correct fix needs separate per-viewpoint translucent
    // buffers; not worth the cost/risk on the vanilla-only path while Sodium —
    // handled by SodiumTranslucencyCompat — is correct.)
    @Inject(method = "translucencySort", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipTranslucencySortForCamera(Vec3d cameraPos, CallbackInfo ci) {
        if (CameraRenderer.isRendering()) ci.cancel();
    }

    // ─── Render the local player in the camera view ──────────────────────────
    // 1.21.9's fillEntityRenderStates skips ANY ClientPlayerEntity that is not
    // the camera's focused entity — vanilla only draws the local player when
    // it IS the focus (third person). During the camera pass the focus is the
    // CameraEntity, so the local player would never appear in the stream.
    // Defeat that instanceof during the camera pass so the player is treated
    // like any other entity; the focused-entity check just above it still
    // keeps the camera entity itself out of its own first-person view.
    // (Only one INSTANCEOF ClientPlayerEntity exists in the method.)
    @org.spongepowered.asm.mixin.injection.Redirect(
            method = "fillEntityRenderStates",
            at = @At(value = "CONSTANT",
                     args = "classValue=net/minecraft/client/network/ClientPlayerEntity"))
    private boolean cameramod$renderLocalPlayerInCameraPass(Object entity, Class<?> type) {
        if (CameraRenderer.isRendering()) return false;
        return type.isInstance(entity);
    }

}
