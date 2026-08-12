package dev.tggamesyt.cameramod.mixin.client;

import dev.tggamesyt.cameramod.CameraEntity;
import dev.tggamesyt.cameramod.CameraServerThing;
import dev.tggamesyt.cameramod.ServerItems;
import dev.tggamesyt.cameramod.client.CameramodClient;
import dev.tggamesyt.cameramod.client.gui.EditCameraScreen;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {

    // EditCameraScreen's "Rotate" mode needs cursor-delta input while a screen
    // is open. The standard Screen.mouseMoved path is dispatched from
    // Mouse.tick() at a rate that depends on input batching, and when the
    // cursor is locked the dispatch can lag a frame or arrive empty (the same
    // tick clears cursorDeltaX/Y after applying them to the player). Catching
    // the raw GLFW cursor-pos event here gives every movement directly to the
    // screen so rotation feels responsive.
    @Inject(method = "onCursorPos", at = @At("HEAD"))
    private void cameramod$forwardRotateCursorPos(long window, double x, double y, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (window != mc.getWindow().getHandle()) return;
        if (mc.currentScreen instanceof EditCameraScreen ecs) {
            ecs.cameramod$onRawCursorPos(x, y);
        }
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void cameramod$interceptScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.currentScreen != null) return;
        if (vertical == 0) return;

        // EditCameraScreen "Move" mode: scroll adjusts the follow distance.
        if (CameramodClient.editMoveCamUuid != null) {
            CameramodClient.onEditMoveScroll((float) vertical);
            ci.cancel();
            return;
        }

        if (CameramodClient.cameraMode) {
            ItemStack held = mc.player.getMainHandStack();
            if (held.isOf(ServerItems.CAMERA_MOVER)) {
                if (CameramodClient.serverHasMod && CameramodClient.moverActive) {
                    ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 0, (float) vertical));
                    ci.cancel();
                } else if (!CameramodClient.serverHasMod && CameramodClient.clientMoverActive) {
                    CameramodClient.onClientMoverScroll((float) vertical);
                    ci.cancel();
                }
                // Mover not active: let vanilla scroll the hotbar slot
                return;
            }
            if (held.isOf(ServerItems.CAMERA_ZOOMER) && mc.player.isSneaking()) {
                if (CameramodClient.serverHasMod && CameramodClient.zoomerActive) {
                    ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 1, (float) vertical));
                } else if (!CameramodClient.serverHasMod) {
                    CameramodClient.onClientZoomerScroll((float) vertical);
                }
                ci.cancel();
                return;
            }
            // All other slots: allow vanilla hotbar scrolling
            return;
        }

        // Normal (non-camera-mode) behavior
        ItemStack held = mc.player.getMainHandStack();

        if (held.isOf(ServerItems.CAMERA_MOVER) && CameramodClient.moverActive) {
            ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 0, (float) vertical));
            ci.cancel();
            return;
        }

        if (held.isOf(ServerItems.CAMERA_ZOOMER) && mc.player.isSneaking() && CameramodClient.zoomerActive) {
            ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 1, (float) vertical));
            ci.cancel();
        }
    }

    /**
     * Handle mouse button presses while one of EditCameraScreen's external modes
     * is active (move mode or select-entity mode). Both modes exit on click so
     * the EditCameraScreen reopens; in select mode a right-click on a non-camera
     * entity sets that entity as the camera's target.
     */
    // 1.21.9: (long, int button, int action, int mods) -> (long, MouseInput, int action)
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void cameramod$interceptButton(long window, net.minecraft.client.input.MouseInput input, int action, CallbackInfo ci) {
        int button = input.button();
        if (action != GLFW.GLFW_PRESS) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null) return; // screen open → vanilla handles it

        if (CameramodClient.editMoveCamUuid != null) {
            CameramodClient.exitEditMoveMode();
            ci.cancel();
            return;
        }

        if (CameramodClient.editSelectCamUuid != null) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                CameramodClient.cancelEditSelectMode();
                ci.cancel();
                return;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && mc.player != null && mc.world != null) {
                Entity hit = cameramod$raycastEntityForSelect(mc);
                if (hit != null) {
                    CameramodClient.applyEditSelectTarget(hit);
                } else {
                    CameramodClient.cancelEditSelectMode();
                }
                ci.cancel();
            }
        }
    }

    private static Entity cameramod$raycastEntityForSelect(MinecraftClient mc) {
        double reach = mc.options.getViewDistance().getValue() * 16.0;
        Vec3d eye  = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0F);
        Vec3d end  = eye.add(look.x * reach, look.y * reach, look.z * reach);
        BlockHitResult br = mc.world.raycast(new RaycastContext(
                eye, end,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                mc.player));
        double bestDistSq = (br != null && br.getType() != HitResult.Type.MISS)
                ? eye.squaredDistanceTo(br.getPos())
                : reach * reach;
        Entity best = null;
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player) continue;
            if (e instanceof CameraEntity) continue; // can't fix/attach to a camera
            Box box = e.getBoundingBox().expand(e.getTargetingMargin());
            var opt = box.raycast(eye, end);
            if (opt.isPresent()) {
                double d = eye.squaredDistanceTo(opt.get());
                if (d < bestDistSq) { bestDistSq = d; best = e; }
            }
        }
        return best;
    }
}
