package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.client.CameraRenderer;
import dev.tggamesyt.cameramod.client.VivecraftCompat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
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
 *
 * <p>1.21.9+ note: the player renderer is still {@code PlayerEntityRenderer}
 * (intermediary {@code class_1007}) and the state is still
 * {@code PlayerEntityRenderState} ({@code class_10055}), but the entity parameter
 * of {@code updateRenderState} is now {@code PlayerLikeEntity} ({@code class_11890})
 * instead of {@code AbstractClientPlayerEntity}. {@code require = 0} so a further
 * signature change just disables the effect instead of breaking mixin apply.
 */
@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererVrMixin {

    @Inject(method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;"
            + "Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("RETURN"), require = 0)
    private void cameramod$forceLocalVrBody(PlayerLikeEntity entity,
            PlayerEntityRenderState state, float tickDelta, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (CameraRenderer.isRendering()
                && VivecraftCompat.isVrPassActive()
                && entity == mc.player) {
            // mc.player is a LivingEntity (ClientPlayerEntity); pass it so the
            // reflective getMainPlayerRotInfo(LivingEntity,float) call resolves.
            VivecraftCompat.applyLocalPlayerVrBody(state, mc.player, tickDelta);
        }
    }
}
