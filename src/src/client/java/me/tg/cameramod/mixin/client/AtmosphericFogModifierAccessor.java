package me.tg.cameramod.mixin.client;

import net.minecraft.client.render.fog.AtmosphericFogModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AtmosphericFogModifier.class)
public interface AtmosphericFogModifierAccessor {
    @Accessor("fogMultiplier")
    float cameramod$getFogMultiplier();

    @Accessor("fogMultiplier")
    void cameramod$setFogMultiplier(float value);
}
