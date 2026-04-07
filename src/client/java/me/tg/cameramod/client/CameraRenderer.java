package me.tg.cameramod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.buffers.GpuBuffer;
import me.tg.cameramod.CameraEntity;
import me.tg.cameramod.Cameramod;
import me.tg.cameramod.ServerItems;
import me.tg.cameramod.SoftCam;
import me.tg.cameramod.mixin.client.CameraAccessor;
import me.tg.cameramod.mixin.client.CloudRendererAccessor;
import me.tg.cameramod.mixin.client.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.CloudRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

public class CameraRenderer {

    private static final int CAPTURE_INTERVAL = 3;

    private static SimpleFramebuffer offscreenFbo;
    private static byte[] frameBytes;
    private static int frameCounter = 0;
    private static boolean rendering = false;
    private static boolean offImageSent = false;

    public static boolean isRendering() {
        return rendering;
    }

    public static void sendOffImage() {
        if (!SoftCam.isInitialized() || Cameramod.softcamCamera == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getResourceManager() == null) return;

        try {
            Identifier offTextureId = Identifier.of(Cameramod.MOD_ID, "textures/gui/off.png");
            var resource = mc.getResourceManager().getResource(offTextureId);
            if (resource.isEmpty()) return;

            try (InputStream is = resource.get().getInputStream();
                 NativeImage image = NativeImage.read(is)) {
                int w = Cameramod.camwidth;
                int h = Cameramod.camheight;
                byte[] frame = new byte[w * h * 3];

                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int srcX = x * image.getWidth() / w;
                        int srcY = y * image.getHeight() / h;
                        int color = image.getColorArgb(srcX, srcY);
                        int di = (y * w + x) * 3;
                        frame[di]     = (byte) (color & 0xFF);         // B
                        frame[di + 1] = (byte) ((color >> 8) & 0xFF);  // G
                        frame[di + 2] = (byte) ((color >> 16) & 0xFF); // R
                    }
                }

                SoftCam.sendFrame(Cameramod.softcamCamera, frame);
                offImageSent = true;
            }
        } catch (Exception e) {
            Cameramod.LOGGER.error("Failed to send off image", e);
        }
    }

    public static void onFrameRendered(GameRenderer gameRenderer, RenderTickCounter tickCounter) {
        if (rendering) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        if (!SoftCam.isInitialized() || Cameramod.softcamCamera == null) return;
        if (CameramodClient.isCamera) return;

        frameCounter++;
        if (frameCounter % CAPTURE_INTERVAL != 0) return;

        Entity camera = getActiveCamera(mc);
        if (camera == null) {
            // No active camera — send off image once
            if (!offImageSent) {
                sendOffImage();
            }
            return;
        }

        // We have an active camera — reset the off image flag
        offImageSent = false;

        rendering = true;

        // --- save state ---
        Entity savedCameraEntity = mc.getCameraEntity();
        MinecraftClientAccessor accessor = (MinecraftClientAccessor) mc;
        Framebuffer mainFbo = accessor.cameramod$getFramebuffer();
        Perspective savedPerspective = mc.options.getPerspective();

        Camera cam = gameRenderer.getCamera();
        CameraAccessor camAccessor = (CameraAccessor) cam;
        float savedCameraY = camAccessor.cameramod$getCameraY();
        float savedLastCameraY = camAccessor.cameramod$getLastCameraY();

        // Save cloud state to prevent wiggling on player's view
        CloudRenderer cloudRenderer = mc.worldRenderer.getCloudRenderer();
        CloudRendererAccessor cloudAccessor = (CloudRendererAccessor) cloudRenderer;
        int savedCloudCenterX = cloudAccessor.cameramod$getCenterX();
        int savedCloudCenterZ = cloudAccessor.cameramod$getCenterZ();

        try {
            int w = Cameramod.camwidth;
            int h = Cameramod.camheight;
            if (offscreenFbo == null) {
                offscreenFbo = new SimpleFramebuffer("cameramod_offscreen", w, h, true);
            }

            // Swap the framebuffer so WorldRenderer.render() targets our FBO
            accessor.cameramod$setFramebuffer(offscreenFbo);

            // Force first-person so camera.update() doesn't use player's perspective
            mc.options.setPerspective(Perspective.FIRST_PERSON);

            // Set cameraY/lastCameraY to the CameraEntity's eye height so the
            // camera view doesn't shift when the player crouches
            float cameraEntityEyeHeight = camera.getStandingEyeHeight();
            camAccessor.cameramod$setCameraY(cameraEntityEyeHeight);
            camAccessor.cameramod$setLastCameraY(cameraEntityEyeHeight);

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
            mc.options.setPerspective(savedPerspective);

            // Restore Camera eye-height interpolation state
            camAccessor.cameramod$setCameraY(savedCameraY);
            camAccessor.cameramod$setLastCameraY(savedLastCameraY);

            // Restore cloud state and force rebuild on next player frame
            cloudAccessor.cameramod$setCenterX(savedCloudCenterX);
            cloudAccessor.cameramod$setCenterZ(savedCloudCenterZ);
            cloudAccessor.cameramod$setRebuild(true);

            // Re-run camera.update() with the player entity to fully restore
            // position, rotation, and all other Camera fields for the next frame
            Entity playerEntity = savedCameraEntity != null ? savedCameraEntity : mc.player;
            float tickProgress = tickCounter.getTickProgress(false);
            boolean thirdPerson = !savedPerspective.isFirstPerson();
            boolean frontView = savedPerspective.isFrontView();
            cam.update(mc.world, playerEntity, thirdPerson, frontView, tickProgress);

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

        // Camera was bound but no longer exists — send off image
        if (!offImageSent) {
            sendOffImage();
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
