package me.tg.cameramod.mixin.client;

import net.minecraft.client.render.CloudRenderer;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldRenderer.class)
public interface WorldRendererAccessor {

    @Accessor("cloudRenderer")
    CloudRenderer cameramod$getCloudRenderer();
}
