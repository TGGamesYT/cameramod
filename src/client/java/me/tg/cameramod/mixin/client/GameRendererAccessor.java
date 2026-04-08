package me.tg.cameramod.mixin.client;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Accessor("guiState")
    GuiRenderState cameramod$getGuiState();

    @Accessor("guiRenderer")
    GuiRenderer cameramod$getGuiRenderer();

    @Accessor("fogRenderer")
    FogRenderer cameramod$getFogRenderer();
}
