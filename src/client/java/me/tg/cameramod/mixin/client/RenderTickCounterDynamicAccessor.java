package me.tg.cameramod.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.render.RenderTickCounter$Dynamic")
public interface RenderTickCounterDynamicAccessor {

    @Accessor("lastTimeMillis")
    long cameramod$getLastTimeMillis();

    @Accessor("lastTimeMillis")
    void cameramod$setLastTimeMillis(long value);
}
