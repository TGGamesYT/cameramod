package me.tg.cameramod.mixin.client;

import net.minecraft.client.render.fog.FogModifier;
import net.minecraft.client.render.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(FogRenderer.class)
public interface FogRendererAccessor {
    @Accessor("FOG_MODIFIERS")
    static List<FogModifier> cameramod$getFogModifiers() {
        throw new AssertionError();
    }
}
