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

    // ─── Skip expensive per-frame effects during camera pass ─────────────────
    // Weather (rain/snow) is visually minor for the stream output and adds
    // non-trivial GPU cost each camera frame, so it stays skipped. Particles,
    // however, ARE drawn in the camera POV (users expect them) — see below.

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

    // Particles ARE rendered during the camera pass: they're billboarded to the
    // active viewpoint, so they draw correctly from the camera and users expect
    // to see them in the stream. (We only skip SPAWNING new weather particles /
    // playing their sound below — that's world mutation, not view-local drawing.)

    @Inject(method = "addWeatherParticlesAndSound", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipWeatherParticlesAndSoundForCamera(CallbackInfo ci) {
        if (CameraRenderer.isRendering()) ci.cancel();
    }

    // ─── Entity list for camera viewpoint ────────────────────────────────────
    // During the camera pass the output list must end up containing EXACTLY the
    // entities visible from the CAMERA's frustum — no more, no less.
    //
    // Why "no more": rendering an entity mutates shared per-frame render state
    // (the per-renderer EntityRenderState, and Sodium/Iris/Embeddium per-entity
    // bookkeeping). For an entity BOTH views render that's harmless — the player
    // pass re-renders it and overwrites the state. But for an entity ONLY the
    // player can see, if the camera renders it the camera leaves that entity's
    // state set for the camera viewpoint and the player's following pass draws it
    // with that stale state — visible as JITTER on entities the camera can't see.
    // This is the bug that ping-ponged between "filter added entities" (356c617)
    // and "add everything" (56b264c): the first only filtered what WE add and
    // left player-only entities the base method (Sodium with culling disabled)
    // had already put in output, so the camera still rendered them; the second
    // removed the filter entirely and reintroduced the jitter outright. Filtering
    // BOTH sides fixes it for good.
    //
    // Why "no less": FPS-optimizing rendering mods cull entities against the
    // PLAYER's chunk-visibility set, dropping entities the camera can see but the
    // player can't (black gaps / missing mobs in the stream). So after pruning we
    // add back every world entity that passes the camera frustum. This also
    // subsumes the old "add the player back" workaround for MC filtering out the
    // active camera entity.
    @Inject(method = "getEntitiesToRender", at = @At("TAIL"), cancellable = true)
    private void cameramod$addAllEntitiesForCameraView(Camera camera, Frustum frustum, List<Entity> output, CallbackInfoReturnable<Boolean> cir) {
        if (!CameraRenderer.isRendering()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;

        // In first-person the active camera entity is the viewpoint and must not
        // appear in its own view. In third-person (FRONT/BACK) preview passes
        // the camera entity IS the subject and must render so it shows up in
        // the EditCameraScreen preview images.
        boolean firstPerson = mc.options.getPerspective() == Perspective.FIRST_PERSON;
        Entity activeCameraEntity = mc.getCameraEntity();

        // Prune entities the camera can't actually see (and the camera entity
        // itself in first person). Removing player-only entities here is what
        // keeps the camera pass from polluting their render state.
        output.removeIf(e ->
                (firstPerson && e == activeCameraEntity)
                        || !frustum.isVisible(e.getBoundingBox()));

        java.util.Set<Entity> existing = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        existing.addAll(output);
        for (Entity e : mc.world.getEntities()) {
            if (firstPerson && e == activeCameraEntity) continue;
            if (!existing.add(e)) continue;
            // Only entities the camera can actually see — adding off-camera
            // entities costs render-state pollution that jitters the player's pass.
            if (!frustum.isVisible(e.getBoundingBox())) continue;
            output.add(e);
        }
        if (!output.isEmpty() && Boolean.FALSE.equals(cir.getReturnValue())) {
            cir.setReturnValue(true);
        } else if (output.isEmpty() && Boolean.TRUE.equals(cir.getReturnValue())) {
            cir.setReturnValue(false);
        }
    }
}
