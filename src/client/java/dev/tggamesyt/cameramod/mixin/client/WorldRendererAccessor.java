package dev.tggamesyt.cameramod.mixin.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.ChunkRenderingDataPreparer;
import net.minecraft.client.render.CloudRenderer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(WorldRenderer.class)
public interface WorldRendererAccessor {

    @Accessor("cloudRenderer")
    CloudRenderer cameramod$getCloudRenderer();

    @Accessor("builtChunks")
    ObjectArrayList<ChunkBuilder.BuiltChunk> cameramod$getBuiltChunks();

    @Accessor("builtChunks")
    @Mutable
    void cameramod$setBuiltChunks(ObjectArrayList<ChunkBuilder.BuiltChunk> list);

    @Accessor("nearbyChunks")
    ObjectArrayList<ChunkBuilder.BuiltChunk> cameramod$getNearbyChunks();

    @Accessor("nearbyChunks")
    @Mutable
    void cameramod$setNearbyChunks(ObjectArrayList<ChunkBuilder.BuiltChunk> list);

    // The occlusion / visible-chunk graph builder. The camera pass swaps in its
    // OWN instance so its viewpoint-bounded BFS never corrupts the player's
    // visible-chunk set (the cause of chunks beyond the camera's render distance
    // jittering in the player's view).
    @Accessor("chunkRenderingDataPreparer")
    ChunkRenderingDataPreparer cameramod$getChunkRenderingDataPreparer();

    @Accessor("chunkRenderingDataPreparer")
    @Mutable
    void cameramod$setChunkRenderingDataPreparer(ChunkRenderingDataPreparer preparer);

    @Accessor("chunks")
    BuiltChunkStorage cameramod$getChunkStorage();

    @Accessor("ticks")
    int cameramod$getTicks();

    // Position the translucent (water/glass) faces were last sorted back-to-front
    // for. WorldRenderer.render() only re-sorts when the camera's block position
    // changes from this. The camera pass would otherwise leave this at the camera
    // entity's block pos, making the player's render re-sort translucency for the
    // camera viewpoint (visible as flickering water). Save/restore around the pass.
    @Accessor("lastTranslucencySortCameraPos")
    BlockPos cameramod$getLastTranslucencySortCameraPos();

    @Accessor("lastTranslucencySortCameraPos")
    void cameramod$setLastTranslucencySortCameraPos(BlockPos pos);

    // Entity list and count used for the F3 "E:" debug line.
    // The camera pass populates renderedEntities with all world entities
    // (via WorldRendererMixin.getEntitiesToRender) and sets renderedEntitiesCount
    // from that list's size. Clear/restore these after the camera pass so the
    // player pass starts with a clean slate and F3 shows only the player's values.
    @Accessor("renderedEntities")
    List<Entity> cameramod$getRenderedEntities();

    @Accessor("renderedEntitiesCount")
    int cameramod$getRenderedEntitiesCount();

    @Accessor("renderedEntitiesCount")
    void cameramod$setRenderedEntitiesCount(int count);
}
