package dev.tggamesyt.cameramod.mixin.client;

import net.minecraft.client.gl.MappableRingBuffer;
import net.minecraft.client.render.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CloudRenderer.class)
public interface CloudRendererAccessor {

    @Accessor("centerX")
    int cameramod$getCenterX();

    @Accessor("centerX")
    void cameramod$setCenterX(int value);

    @Accessor("centerZ")
    int cameramod$getCenterZ();

    @Accessor("centerZ")
    void cameramod$setCenterZ(int value);

    @Accessor("rebuild")
    boolean cameramod$getRebuild();

    @Accessor("rebuild")
    void cameramod$setRebuild(boolean value);

    // The built cloud-face geometry buffer for the current cell. Swapped per
    // viewpoint so the camera keeps its own cell geometry and neither pass
    // forces the other to rebuild.
    @Accessor("cloudFacesBuffer")
    MappableRingBuffer cameramod$getCloudFacesBuffer();

    @Accessor("cloudFacesBuffer")
    void cameramod$setCloudFacesBuffer(MappableRingBuffer value);

    // Instance count that goes with the built geometry above — must travel with it.
    @Accessor("instanceCount")
    int cameramod$getInstanceCount();

    @Accessor("instanceCount")
    void cameramod$setInstanceCount(int value);
}
