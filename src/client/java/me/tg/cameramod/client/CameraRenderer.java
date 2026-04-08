package me.tg.cameramod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.buffers.GpuBuffer;
import me.tg.cameramod.CameraEntity;
import me.tg.cameramod.Cameramod;
import me.tg.cameramod.SoftCam;
import me.tg.cameramod.mixin.client.CameraAccessor;
import me.tg.cameramod.mixin.client.GameRendererAccessor;
import me.tg.cameramod.mixin.client.MinecraftClientAccessor;
import me.tg.cameramod.mixin.client.WorldRendererAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.GameRules;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

public class CameraRenderer {

    private static final int CAPTURE_INTERVAL = 3;

    private static SimpleFramebuffer offscreenFbo;
    private static byte[] frameBytes;
    private static byte[] offImageFrame;
    private static int frameCounter = 0;
    private static boolean rendering = false;

    public static boolean isRendering() {
        return rendering;
    }

    private static void ensureOffImage() {
        if (offImageFrame != null) return;
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
                offImageFrame = new byte[w * h * 3];

                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int srcX = x * image.getWidth() / w;
                        int srcY = y * image.getHeight() / h;
                        int color = image.getColorArgb(srcX, srcY);
                        int di = (y * w + x) * 3;
                        offImageFrame[di]     = (byte) (color & 0xFF);         // B
                        offImageFrame[di + 1] = (byte) ((color >> 8) & 0xFF);  // G
                        offImageFrame[di + 2] = (byte) ((color >> 16) & 0xFF); // R
                    }
                }
            }
        } catch (Exception e) {
            Cameramod.LOGGER.error("Failed to load off image", e);
        }
    }

    private static void sendOffImage() {
        if (!SoftCam.isInitialized() || Cameramod.softcamCamera == null) return;
        ensureOffImage();
        if (offImageFrame != null) {
            SoftCam.sendFrame(Cameramod.softcamCamera, offImageFrame);
        }
    }

    // Bound camera UUID — synced from server via packet
    private static UUID boundCameraUuid = null;

    public static void setBoundCamera(UUID cameraUuid) {
        boundCameraUuid = cameraUuid;
    }

    public static void clearBoundCamera() {
        boundCameraUuid = null;
    }

    public static void onFrameRendered(GameRenderer gameRenderer, RenderTickCounter tickCounter) {
        if (rendering) return;
        if (!SoftCam.isInitialized() || Cameramod.softcamCamera == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();

        frameCounter++;
        if (frameCounter % CAPTURE_INTERVAL != 0) return;

        // When not in a world (main menu, loading, etc.), send the off image
        if (mc.world == null || mc.player == null) {
            sendOffImage();
            return;
        }
        if (CameramodClient.isCamera) return;

        Entity camera = getActiveCamera(mc);
        if (camera == null) {
            sendOffImage();
            return;
        }

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

        // Save the player's frustum so the camera pass doesn't corrupt entity culling
        WorldRendererAccessor wrAccessor = (WorldRendererAccessor) mc.worldRenderer;
        Frustum savedFrustum = wrAccessor.cameramod$getFrustum();

        try {
            int w = Cameramod.camwidth;
            int h = Cameramod.camheight;
            if (offscreenFbo == null) {
                offscreenFbo = new SimpleFramebuffer("cameramod_offscreen", w, h, true);
            }

            accessor.cameramod$setFramebuffer(offscreenFbo);
            mc.options.setPerspective(Perspective.FIRST_PERSON);

            float cameraEntityEyeHeight = camera.getStandingEyeHeight();
            camAccessor.cameramod$setCameraY(cameraEntityEyeHeight);
            camAccessor.cameramod$setLastCameraY(cameraEntityEyeHeight);

            mc.setCameraEntity(camera);

            gameRenderer.renderWorld(tickCounter);

            // Render chat overlay into the offscreen FBO if cameraSeesChat is enabled
            if (getGameruleBool(mc, Cameramod.CAMERA_SEES_CHAT)) {
                renderChatOverlay(mc, gameRenderer, w, h);
            }

            // Check cameraFlipped gamerule via integrated server (or default false)
            boolean flipped = getGameruleBool(mc, Cameramod.CAMERA_FLIPPED);
            captureFramebuffer(offscreenFbo, w, h, flipped);

        } catch (Exception e) {
            Cameramod.LOGGER.error("CameraRenderer second pass failed", e);
        } finally {
            accessor.cameramod$setFramebuffer(mainFbo);
            mc.setCameraEntity(savedCameraEntity);
            mc.options.setPerspective(savedPerspective);

            camAccessor.cameramod$setCameraY(savedCameraY);
            camAccessor.cameramod$setLastCameraY(savedLastCameraY);

            // Restore the player's frustum so entity culling isn't corrupted
            wrAccessor.cameramod$setFrustum(savedFrustum);

            Entity playerEntity = savedCameraEntity != null ? savedCameraEntity : mc.player;
            float tickProgress = tickCounter.getTickProgress(false);
            boolean thirdPerson = !savedPerspective.isFirstPerson();
            boolean frontView = savedPerspective.isFrontView();
            cam.update(mc.world, playerEntity, thirdPerson, frontView, tickProgress);

            rendering = false;
        }
    }

    private static boolean getGameruleBool(MinecraftClient mc, GameRules.Key<GameRules.BooleanRule> key) {
        MinecraftServer server = mc.getServer();
        if (server != null) {
            return server.getGameRules().getBoolean(key);
        }
        return false;
    }

    private static Entity getActiveCamera(MinecraftClient mc) {
        if (boundCameraUuid == null) return null;

        for (Entity entity : mc.world.getEntities()) {
            if (entity.getUuid().equals(boundCameraUuid) && entity instanceof CameraEntity) {
                return entity;
            }
        }
        return null;
    }

    private static void renderChatOverlay(MinecraftClient mc, GameRenderer gameRenderer, int fbWidth, int fbHeight) {
        try {
            GameRendererAccessor grAccessor = (GameRendererAccessor) gameRenderer;
            GuiRenderState guiState = grAccessor.cameramod$getGuiState();
            GuiRenderer guiRenderer = grAccessor.cameramod$getGuiRenderer();
            FogRenderer fogRenderer = grAccessor.cameramod$getFogRenderer();

            // Clear GUI state and create fresh DrawContext
            guiState.clear();
            DrawContext drawContext = new DrawContext(mc, guiState);

            // Render chat into the GUI state
            float scaleFactor = (float) mc.getWindow().getScaleFactor();
            int scaledWidth = (int) (fbWidth / scaleFactor);
            int scaledHeight = (int) (fbHeight / scaleFactor);
            ChatHud chatHud = mc.inGameHud.getChatHud();
            chatHud.render(drawContext, mc.inGameHud.getTicks(), scaledWidth / 2, scaledHeight, false);

            // Flush accumulated GUI draws to the currently bound FBO (our offscreen FBO)
            guiRenderer.render(fogRenderer.getFogBuffer(FogRenderer.FogType.NONE));
        } catch (Exception e) {
            Cameramod.LOGGER.error("Failed to render chat overlay for camera", e);
        }
    }

    private static void captureFramebuffer(Framebuffer framebuffer, int width, int height, boolean flipped) {
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
                                // If flipped, mirror horizontally
                                int outX = flipped ? (sw - 1 - x) : x;
                                int di = dstRow + outX * 3;
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
