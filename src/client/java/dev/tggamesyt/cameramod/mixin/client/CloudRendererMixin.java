package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.client.CameraRenderer;
import net.minecraft.client.render.CloudRenderer;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(CloudRenderer.class)
public class CloudRendererMixin {

    // CloudRenderer caches a packed cloud-face GPU buffer keyed on the cell
    // (centerX, centerZ) that the cameraPos argument falls into. A cameraPos in
    // a different cell than the cache triggers a full vbo rebuild.
    //
    // With the camera in a different cloud-cell than the player, naive rendering
    // means a double rebuild every frame: the camera pass rebuilds for the
    // camera's cell, then the player pass rebuilds back for the player's — the
    // thrash that causes cloud stutter.
    //
    // The MAIN stream pass solves this properly: CameraRenderer swaps in a
    // camera-dedicated cloud-face buffer + cell state around the camera pass, so
    // the camera renders from its OWN cell at its OWN position (clouds correctly
    // world-fixed, not dragged around by player movement) without ever rebuilding
    // the player's geometry. While that swap is active we must NOT substitute the
    // player position — the camera's real position is exactly what we want.
    //
    // The lightweight preview passes (EditCameraScreen) don't get a dedicated
    // buffer, so for them we keep the old trick: substitute the player's saved
    // position so the preview reuses the player's cell and never triggers a
    // rebuild. Preview clouds being aligned to the player is invisible at
    // thumbnail size and avoids stutter while editing.
    @ModifyVariable(
            method = "renderClouds(ILnet/minecraft/client/option/CloudRenderMode;FLnet/minecraft/util/math/Vec3d;JF)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0)
    private Vec3d cameramod$alignCloudCellToPlayer(Vec3d cameraPos) {
        if (!CameraRenderer.isRendering()) return cameraPos;
        // Main camera pass with the dedicated-buffer swap active: use real pos.
        if (CameraRenderer.isCameraCloudStateActive()) return cameraPos;
        // Preview pass: fall back to player-cell alignment.
        Vec3d saved = CameraRenderer.getSavedPlayerCameraPos();
        return saved != null ? saved : cameraPos;
    }
}
