package me.tg.cameramod.mixin.client;

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
}
