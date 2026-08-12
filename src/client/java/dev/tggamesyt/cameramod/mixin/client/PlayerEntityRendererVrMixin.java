package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.client.CameraRenderer;
import dev.tggamesyt.cameramod.client.VivecraftCompat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draw the LOCAL player as Vivecraft's VR avatar in the camera stream.
 *
 * <p>Vivecraft renders a player's VR body only when its render state carries a
 * {@code RotInfo}. For the local player it fills that in
 * ({@code ClientVRPlayers.getRotationsForPlayer}) only when {@code VR_RUNNING}
 * AND {@code mc.getCameraEntity() == mc.player} — both of which the camera pass
 * deliberately breaks (mono levers force {@code VR_RUNNING=false}; the
 * getCameraEntity override points at the CameraEntity). So Vivecraft's own
 * {@code updateRenderState} hook nulls the RotInfo and the player renders as a
 * plain vanilla model in the stream.
 *
 * <p>At the RETURN of {@code updateRenderState} — after Vivecraft's HEAD hook has
 * nulled it — re-apply the live main-player RotInfo via
 * {@link VivecraftCompat#applyLocalPlayerVrBody}. Only for {@code mc.player},
 * only during the camera pass, and only while VR is actively running
 * ({@link VivecraftCompat#isVrPassActive()} — NOT the session-level
 * {@code isVrMode}, which stays true when the headset is removed or VR is
 * hot-switch-paused; keying off it would keep drawing the last frozen VR pose
 * after VR is disabled in-game). A no-op otherwise / without Vivecraft.
 * {@code require = 0} so a signature change on a future MC version just disables
 * the effect instead of breaking mixin apply.
 */
@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererVrMixin {

    @Inject(method = "updateRenderState(Lnet/minecraft/client/network/AbstractClientPlayerEntity;"
            + "Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("RETURN"), require = 0)
    private void cameramod$forceLocalVrBody(AbstractClientPlayerEntity entity,
            PlayerEntityRenderState state, float tickDelta, CallbackInfo ci) {
        if (CameraRenderer.isRendering()
                && VivecraftCompat.isVrPassActive()
                && entity == MinecraftClient.getInstance().player) {
            VivecraftCompat.applyLocalPlayerVrBody(state, entity, tickDelta);
        }
    }
}
