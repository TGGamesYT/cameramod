package me.tg.cameramod.mixin.client;

import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightmapTextureManager.class)
public interface LightmapTextureManagerAccessor {

    @Accessor("dirty")
    void cameramod$setDirty(boolean value);
}
