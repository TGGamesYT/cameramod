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
    // Weather (rain/snow) and particles are visually irrelevant for the stream
    // output and add non-trivial GPU cost each camera frame.

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

    @Inject(method = "renderParticles", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipParticlesForCamera(CallbackInfo ci) {
        if (CameraRenderer.isRendering()) ci.cancel();
    }

    @Inject(method = "addWeatherParticlesAndSound", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipWeatherParticlesAndSoundForCamera(CallbackInfo ci) {
        if (CameraRenderer.isRendering()) ci.cancel();
    }

    // ─── Entity list for camera viewpoint ────────────────────────────────────
    // FPS-optimizing rendering mods (Sodium and derivatives like Iris, Indium,
    // Embeddium…) replace MC's entity culling with a per-frame filter keyed on
    // the player's chunk visibility set. Our second-viewpoint render inherits
    // that filter, so entities (and the player itself) outside the player's
    // view get dropped from the camera image. Add back every entity in the
    // world entity manager during the camera pass and let the downstream
    // per-entity frustum test cull what's actually off-screen. This also
    // subsumes the old "add the player back" workaround for MC filtering out
    // the active camera entity.
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
        if (firstPerson && activeCameraEntity != null) {
            output.removeIf(e -> e == activeCameraEntity);
        }

        java.util.Set<Entity> existing = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        existing.addAll(output);
        for (Entity e : mc.world.getEntities()) {
            if (firstPerson && e == activeCameraEntity) continue;
            if (!existing.add(e)) continue;
            output.add(e);
        }
        if (!output.isEmpty() && Boolean.FALSE.equals(cir.getReturnValue())) {
            cir.setReturnValue(true);
        }
    }
}
