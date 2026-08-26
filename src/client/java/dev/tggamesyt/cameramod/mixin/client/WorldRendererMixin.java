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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    // True once, if a Sodium-family renderer is present. Sodium replaces the
    // vanilla chunk pipeline, so the vanilla-only tweaks below must not interfere.
    private static final boolean cameramod$SODIUM_LOADED = cameramod$detectSodium();
    private static boolean cameramod$detectSodium() {
        try {
            Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

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

    // ─── Camera pass is a passive renderer: it never builds chunks (vanilla) ──
    // updateChunks rebuilds dirty chunk meshes and their translucency sort. The
    // camera pass runs first each frame; if it builds, chunks rebuilt during it
    // get water sorted for the camera's position, then the async player sort
    // runs late and flips them back — visible as flashy/wrong-viewpoint water.
    // Skipping it makes the camera render only what the player has already built.
    // No-op under Sodium.
    @Inject(method = "updateChunks", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipChunkBuildForCamera(Camera camera, CallbackInfo ci) {
        if (!cameramod$SODIUM_LOADED && CameraRenderer.isRendering()) ci.cancel();
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
    // fillEntityRenderStates skips a ClientPlayerEntity unless it IS the camera's
    // focused entity — vanilla only draws the local player in third person. The
    // vanilla check is:
    //     if (entity instanceof ClientPlayerEntity
    //             && camera.getFocusedEntity() != entity) continue;   // skip
    // During the camera pass the focus is the CameraEntity, so the local player
    // would never appear in the stream.
    //
    // Redirect the getFocusedEntity() call INSIDE that check (ordinal 3 — the
    // fourth and last in the method) to return the local player during the camera
    // pass, so `!= entity` is false for the local player and it renders. Only
    // that one call is touched, so the earlier "don't draw the focused entity in
    // first person" logic (ordinals 0-2) is untouched, and instanceof
    // ClientPlayerEntity is only ever true for the local player anyway.
    //
    // This replaces an @At("CONSTANT", classValue=...) redirect of the instanceof:
    // loom does NOT remap classValue, so that form scanned 0 targets and crashed
    // mixin apply in a built (remapped) jar while working in dev (named mappings).
    // An INVOKE target descriptor IS remapped in place, so this survives remapping.
    @Redirect(
            method = "fillEntityRenderStates",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/render/Camera;getFocusedEntity()Lnet/minecraft/entity/Entity;",
                     ordinal = 3))
    private Entity cameramod$renderLocalPlayerInCameraPass(Camera camera) {
        if (CameraRenderer.isRendering()) {
            Entity player = MinecraftClient.getInstance().player;
            if (player != null) return player;
        }
        return camera.getFocusedEntity();
    }

}
