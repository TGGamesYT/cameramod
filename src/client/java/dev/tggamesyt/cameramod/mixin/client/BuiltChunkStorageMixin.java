package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.client.CameraRenderer;
import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.util.math.ChunkSectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops the camera pass from thrashing the player's chunks when the player is
 * far from the camera (vanilla terrain renderer only — Sodium has its own
 * position-keyed section cache and is unaffected).
 *
 * <p>Vanilla keeps ONE fixed-size chunk grid ({@link BuiltChunkStorage}) sized
 * by render distance and centered on the camera chunk. {@code setupTerrain}
 * calls {@link BuiltChunkStorage#updateCameraPosition} every frame, which
 * re-maps grid slots to the new center and calls {@code BuiltChunk.setSectionPos}
 * on any slot whose world position changed — discarding that chunk's compiled
 * geometry and scheduling a rebuild.
 *
 * <p>Because we render twice per frame (camera pass first, then the player), the
 * grid is recentered onto the camera and then back onto the player every frame.
 * When the player is within render distance of the camera this changes few or no
 * slots (harmless), but once the player walks beyond the camera's render
 * distance the two render-distance spheres stop overlapping, so the band of
 * chunks near the player (outside the camera's distance) is evicted by the
 * camera pass and rebuilt by the player pass on every single frame — the chunks
 * visibly flicker/jitter in the player's own view.
 *
 * <p>Fix: during the camera pass, do not recenter the grid at all — it stays
 * player-centered, so the player's chunks are never evicted/rebuilt by our
 * second render. The camera then renders from the player-centered grid. The
 * cost is that a vanilla camera can only show terrain that is within the
 * player's loaded grid; geometry further out appears empty (in practice that
 * geometry is usually unloaded anyway when the player is that far from the
 * camera). Sodium users are unaffected — Sodium keeps its own position-keyed,
 * unbounded section cache and never recenters a fixed grid, so it renders both
 * viewpoints fully with no thrash.
 *
 * <p>An earlier version only skipped the recenter when the camera was beyond the
 * grid extent, but at moderate separation the player's far-edge chunks (on the
 * side away from the camera) still fell outside a camera-centered grid and were
 * evicted, so the jitter persisted. Skipping unconditionally is what actually
 * keeps the player's terrain stable.
 */
@Mixin(BuiltChunkStorage.class)
public abstract class BuiltChunkStorageMixin {

    @Inject(method = "updateCameraPosition", at = @At("HEAD"), cancellable = true)
    private void cameramod$skipRecenterDuringCameraPass(ChunkSectionPos pos, CallbackInfo ci) {
        // Only the player pass is allowed to move the shared chunk grid. The
        // camera pass renders from wherever the player left it.
        if (CameraRenderer.isRendering()) {
            ci.cancel();
        }
    }
}
