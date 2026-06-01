package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.client.CameramodClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * In camera mode the player's real held item must stay invisible to other
 * clients. Vanilla ClientPlayerEntity.swingHand both sets the local swing
 * flags AND sends HandSwingC2SPacket so the server broadcasts an animation
 * with the player's *server-side* held item (the real one, not our virtual
 * camera item). Suppress only the packet — the local visual still plays.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntitySwingMixin {

    @Redirect(
        method = "swingHand(Lnet/minecraft/util/Hand;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayNetworkHandler;sendPacket(Lnet/minecraft/network/packet/Packet;)V"
        )
    )
    private void cameramod$skipSwingPacket(ClientPlayNetworkHandler handler, Packet<?> packet) {
        if (CameramodClient.cameraMode) return;
        handler.sendPacket(packet);
    }
}
