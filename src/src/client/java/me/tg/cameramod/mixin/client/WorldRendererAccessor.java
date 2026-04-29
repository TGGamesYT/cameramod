package me.tg.cameramod.mixin.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.CloudRenderer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldRenderer.class)
public interface WorldRendererAccessor {

    @Accessor("cloudRenderer")
    CloudRenderer cameramod$getCloudRenderer();

    @Accessor("builtChunks")
    ObjectArrayList<ChunkBuilder.BuiltChunk> cameramod$getBuiltChunks();

    @Accessor("nearbyChunks")
    ObjectArrayList<ChunkBuilder.BuiltChunk> cameramod$getNearbyChunks();

    @Accessor("chunks")
    BuiltChunkStorage cameramod$getChunkStorage();

    @Accessor("ticks")
    int cameramod$getTicks();
}
