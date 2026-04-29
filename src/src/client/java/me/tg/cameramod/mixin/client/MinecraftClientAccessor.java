package me.tg.cameramod.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {

    @Accessor("framebuffer")
    Framebuffer cameramod$getFramebuffer();

    @Mutable
    @Accessor("framebuffer")
    void cameramod$setFramebuffer(Framebuffer framebuffer);
}
