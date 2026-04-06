package me.tg.cameramod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.buffers.GpuBuffer;
import me.tg.cameramod.CameraEntity;
import me.tg.cameramod.Cameramod;
import me.tg.cameramod.ServerItems;
import me.tg.cameramod.SoftCam;
import me.tg.cameramod.mixin.client.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

/**
 * Secondary game renderer.
 *
 * After each player frame, swaps mc.framebuffer to an offscreen FBO,
 * swaps mc.cameraEntity to the bound camera, calls renderWorld() for
 * a second pass, captures the result, and restores everything.
 * The player never sees any flicker.
 */
public class CameraRenderer {

    private static final int CAPTURE_INTERVAL = 3;

    private static SimpleFramebuffer offscreenFbo;
    private static byte[] frameBytes;
    private static int frameCounter = 0;
    private static boolean rendering = false;

    public static void onFrameRendered(GameRenderer gameRenderer, RenderTickCounter tickCounter) {
        if (rendering) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        if (!SoftCam.isInitialized() || Cameramod.softcamCamera == null) return;
        if (CameramodClient.isCamera) return;

        frameCounter++;
        if (frameCounter % CAPTURE_INTERVAL != 0) return;

        Entity camera = getActiveCamera(mc);
        if (camera == null) return;

        rendering = true;

        // --- save state ---
        Entity savedCameraEntity = mc.getCameraEntity();
        MinecraftClientAccessor accessor = (MinecraftClientAccessor) mc;
        Framebuffer mainFbo = accessor.cameramod$getFramebuffer();

        try {
            int w = Cameramod.camwidth;
            int h = Cameramod.camheight;
            if (offscreenFbo == null) {
                offscreenFbo = new SimpleFramebuffer("cameramod_offscreen", w, h, true);
            }

            // Swap the framebuffer so WorldRenderer.render() targets our FBO
            accessor.cameramod$setFramebuffer(offscreenFbo);

            // Swap camera entity so renderWorld uses the camera's POV
            mc.setCameraEntity(camera);

            // Second render pass — only the world, no GUI
            gameRenderer.renderWorld(tickCounter);

            // Capture the offscreen FBO
            captureFramebuffer(offscreenFbo, w, h);

        } catch (Exception e) {
            Cameramod.LOGGER.error("CameraRenderer second pass failed", e);
        } finally {
            // Restore everything
            accessor.cameramod$setFramebuffer(mainFbo);
            mc.setCameraEntity(savedCameraEntity);
            rendering = false;
        }
    }

    private static Entity getActiveCamera(MinecraftClient mc) {
        UUID playerUuid = mc.player.getUuid();
        UUID cameraUuid = ServerItems.CAMERA_COMMAND_STORAGE.get(playerUuid);
        if (cameraUuid == null) return null;

        for (Entity entity : mc.world.getEntities()) {
            if (entity.getUuid().equals(cameraUuid) && entity instanceof CameraEntity) {
                return entity;
            }
        }
        return null;
    }

    private static void captureFramebuffer(Framebuffer framebuffer, int width, int height) {
        GpuTexture gpuTexture = framebuffer.getColorAttachment();
        if (gpuTexture == null) return;

        GpuBuffer gpuBuffer = RenderSystem.getDevice().createBuffer(
                () -> "CameraRenderer buffer", 9,
                width * height * gpuTexture.getFormat().pixelSize()
        );

        var encoder = RenderSystem.getDevice().createCommandEncoder();

        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
                gpuTexture, gpuBuffer, 0, () -> {
                    try (GpuBuffer.MappedView mappedView = encoder.mapBuffer(gpuBuffer, true, false)) {
                        ByteBuffer src = mappedView.data();
                        int actualStride = src.capacity() / height;

                        final int sw = Cameramod.camwidth;
                        final int sh = Cameramod.camheight;
                        final int rowLen = sw * 3;
                        final int frameSize = rowLen * sh;

                        if (frameBytes == null || frameBytes.length != frameSize)
                            frameBytes = new byte[frameSize];
                        Arrays.fill(frameBytes, (byte) 0);

                        for (int y = 0; y < Math.min(height, sh); y++) {
                            int srcRow = y * actualStride;
                            int dstRow = (sh - 1 - y) * rowLen;
                            for (int x = 0; x < Math.min(width, sw); x++) {
                                int si = srcRow + x * 4;
                                int di = dstRow + x * 3;
                                frameBytes[di]     = src.get(si + 2); // B
                                frameBytes[di + 1] = src.get(si + 1); // G
                                frameBytes[di + 2] = src.get(si);     // R
                            }
                        }

                        SoftCam.sendFrame(Cameramod.softcamCamera, frameBytes);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        gpuBuffer.close();
                    }
                }, 0
        );
    }
}
