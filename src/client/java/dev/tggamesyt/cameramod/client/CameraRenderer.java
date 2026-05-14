package dev.tggamesyt.cameramod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.buffers.GpuBuffer;
import dev.tggamesyt.cameramod.CameraEntity;
import dev.tggamesyt.cameramod.Cameramod;
import dev.tggamesyt.cameramod.SoftCam;
import dev.tggamesyt.cameramod.mixin.client.CameraAccessor;
import dev.tggamesyt.cameramod.mixin.client.GameRendererAccessor;
import dev.tggamesyt.cameramod.mixin.client.LightmapTextureManagerAccessor;
import dev.tggamesyt.cameramod.mixin.client.MinecraftClientAccessor;
import dev.tggamesyt.cameramod.mixin.client.WorldRendererAccessor;
import dev.tggamesyt.cameramod.mixin.client.FogRendererAccessor;
import dev.tggamesyt.cameramod.mixin.client.AtmosphericFogModifierAccessor;
import net.minecraft.client.render.fog.AtmosphericFogModifier;
import net.minecraft.client.render.fog.FogModifier;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.render.chunk.ChunkRenderData;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameRules;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class CameraRenderer {

    private static SimpleFramebuffer offscreenFbo;
    private static byte[] frameBytes;

    // Saved Camera cameraY/lastCameraY for the camera entity pass —
    // prevents the player's sneak bob from leaking into the camera view.
    private static float savedCamCameraY = Float.NaN;
    private static float savedCamLastCameraY = Float.NaN;
    // Wall-clock gate: send at most one frame per (1 / camframerate) seconds,
    // independent of the game's render FPS.
    private static long lastFrameSendNanos = 0L;
    private static boolean rendering = false;

    // Active camera zoom level (set during render pass for FOV override)
    private static float activeZoomLevel = 1.0f;

    // Last good frame from the camera (used when player is too far to render terrain)
    private static byte[] frozenFrame = null;

    // Off image animation state
    private static List<byte[]> offImageFrames;  // null = not loaded yet
    private static boolean offImageLoaded = false;
    private static int offImageFps = 20;          // default fps
    private static double offImageFrameAccumulator = 0; // fractional animation frame position

    public static boolean isRendering() {
        return rendering;
    }

    public static float getActiveZoomLevel() {
        return activeZoomLevel;
    }

    private static byte[] loadImageToBGR(MinecraftClient mc, Identifier textureId) {
        try {
            var resource = mc.getResourceManager().getResource(textureId);
            if (resource.isEmpty()) return null;

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
                return frame;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static int readIntResource(MinecraftClient mc, Identifier id, int defaultValue) {
        try {
            var resource = mc.getResourceManager().getResource(id);
            if (resource.isEmpty()) return defaultValue;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.get().getInputStream(), StandardCharsets.UTF_8))) {
                return Integer.parseInt(reader.readLine().trim());
            }
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static void ensureOffImage() {
        if (offImageLoaded) return;
        if (!SoftCam.isInitialized() || Cameramod.softcamCamera == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getResourceManager() == null) return;

        offImageLoaded = true;

        // Check for animated off image: off.count defines frame count
        Identifier countId = Identifier.of(Cameramod.MOD_ID, "textures/gui/off.count");
        int count = readIntResource(mc, countId, -1);

        if (count > 0) {
            // Load animation frames: off_0.png .. off_(count-1).png
            Identifier fpsId = Identifier.of(Cameramod.MOD_ID, "textures/gui/off.fps");
            offImageFps = readIntResource(mc, fpsId, 20);
            if (offImageFps < 1) offImageFps = 1;

            offImageFrames = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Identifier frameId = Identifier.of(Cameramod.MOD_ID, "textures/gui/off_" + i + ".png");
                byte[] frame = loadImageToBGR(mc, frameId);
                if (frame != null) {
                    offImageFrames.add(frame);
                } else {
                    Cameramod.LOGGER.warn("Missing off image frame: off_{}.png", i);
                }
            }

            if (offImageFrames.isEmpty()) {
                Cameramod.LOGGER.error("off.count={} but no off_N.png frames found, falling back to off.png", count);
                offImageFrames = null;
            } else {
                Cameramod.LOGGER.info("Loaded {} off image frames at {} fps", offImageFrames.size(), offImageFps);
            }
        }

        // If no animation, load the static off.png
        if (offImageFrames == null) {
            Identifier offTextureId = Identifier.of(Cameramod.MOD_ID, "textures/gui/off.png");
            byte[] frame = loadImageToBGR(mc, offTextureId);
            if (frame != null) {
                offImageFrames = new ArrayList<>();
                offImageFrames.add(frame);
                offImageFps = 1; // static, doesn't matter
            } else {
                Cameramod.LOGGER.error("Failed to load off.png");
            }
        }
    }

    private static void sendOffImage() {
        ensureOffImage();
        if (offImageFrames == null || offImageFrames.isEmpty()) return;

        byte[] frame;
        if (offImageFrames.size() == 1) {
            frame = offImageFrames.get(0);
        } else {
            int frameIndex = ((int) offImageFrameAccumulator) % offImageFrames.size();
            frame = offImageFrames.get(frameIndex);
            offImageFrameAccumulator += (double) offImageFps / Cameramod.camframerate;
            if (offImageFrameAccumulator >= offImageFrames.size()) {
                offImageFrameAccumulator -= offImageFrames.size() * Math.floor(offImageFrameAccumulator / offImageFrames.size());
            }
        }

        if (Cameramod.softcamCamera != null) {
            SoftCam.sendFrame(Cameramod.softcamCamera, frame);
        }
        CameraStreamServer.pushFrame(frame, Cameramod.camwidth, Cameramod.camheight);
    }

    // Bound camera UUID — synced from server via packet
    private static UUID boundCameraUuid = null;

    // Streaming toggle: when false, always show off image regardless of camera state
    private static boolean streamingEnabled = false;

    // Gamerule values synced from server (type=3 cameraSeesChat, type=4 cameraFlipped)
    private static boolean cameraSeesChatSynced = false;
    private static boolean cameraFlippedSynced = false;

    // Local client-side overrides (null = use server value)
    private static Boolean cameraSeesChatLocal = null;
    private static Boolean cameraFlippedLocal = null;

    public static void setLocalSeesChat(Boolean val) { cameraSeesChatLocal = val; }
    public static void setLocalFlipped(Boolean val) { cameraFlippedLocal = val; }
    public static Boolean getLocalSeesChat() { return cameraSeesChatLocal; }
    public static Boolean getLocalFlipped() { return cameraFlippedLocal; }

    public static UUID getBoundCameraUuid() {
        return boundCameraUuid;
    }

    public static void setBoundCamera(UUID cameraUuid) {
        boundCameraUuid = cameraUuid;
    }

    public static void clearBoundCamera() {
        boundCameraUuid = null;
        frozenFrame = null;
    }

    public static void setStreamingEnabled(boolean enabled) {
        streamingEnabled = enabled;
    }

    public static boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public static void setCameraSeesChat(boolean value) {
        cameraSeesChatSynced = value;
    }

    public static void setCameraFlipped(boolean value) {
        cameraFlippedSynced = value;
    }

    // Flag: set when this frame should capture player POV at RETURN of render()
    private static boolean capturePlayerPov = false;

    /**
     * Called at RETURN of GameRenderer.render() — captures player POV when no camera is bound.
     */
    public static void onFrameFinished() {
        if (!capturePlayerPov) return;
        capturePlayerPov = false;

        boolean hasSoftCam = SoftCam.isInitialized() && Cameramod.softcamCamera != null;
        if (!hasSoftCam && !CameraStreamServer.hasClients()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getFramebuffer() == null) return;

        boolean flipped = getGameruleBool(Cameramod.CAMERA_FLIPPED);
        captureFramebuffer(mc.getFramebuffer(), Cameramod.camwidth, Cameramod.camheight, flipped);
    }

    public static void onFrameRendered(GameRenderer gameRenderer, RenderTickCounter tickCounter) {
        if (rendering) return;
        boolean hasSoftCam = SoftCam.isInitialized() && Cameramod.softcamCamera != null;
        if (!hasSoftCam && !CameraStreamServer.hasClients()) return;

        MinecraftClient mc = MinecraftClient.getInstance();

        float rate = Cameramod.camframerate;
        if (rate <= 0f) return;
        long now = System.nanoTime();
        long intervalNanos = (long) (1_000_000_000.0 / rate);
        if (now - lastFrameSendNanos < intervalNanos) return;
        lastFrameSendNanos = now;

        // When not in a world (main menu, loading, etc.), send the off image
        if (mc.world == null || mc.player == null) {
            sendOffImage();
            return;
        }
        if (CameramodClient.isCamera) return;

        // Streaming disabled → always show off image
        if (!streamingEnabled) {
            sendOffImage();
            return;
        }

        // No camera bound → schedule player POV capture at end of this frame
        if (boundCameraUuid == null) {
            capturePlayerPov = true;
            return;
        }

        // Try to find the real camera entity in the client world
        Entity camera = findRealCamera(mc);
        if (camera == null) {
            // Camera entity not found (player too far) — send frozen frame or off image
            if (frozenFrame != null) {
                if (hasSoftCam) SoftCam.sendFrame(Cameramod.softcamCamera, frozenFrame);
                CameraStreamServer.pushFrame(frozenFrame, Cameramod.camwidth, Cameramod.camheight);
            } else {
                sendOffImage();
            }
            return;
        }

        // Set zoom level for FOV override
        if (camera instanceof CameraEntity ce) {
            activeZoomLevel = ce.getZoomLevel();
        } else {
            activeZoomLevel = 1.0f;
        }

        rendering = true;

        Entity savedCameraEntity = mc.getCameraEntity();
        MinecraftClientAccessor accessor = (MinecraftClientAccessor) mc;
        Framebuffer mainFbo = accessor.cameramod$getFramebuffer();
        Perspective savedPerspective = mc.options.getPerspective();

        try {
            int w = Cameramod.camwidth;
            int h = Cameramod.camheight;
            if (offscreenFbo == null) {
                offscreenFbo = new SimpleFramebuffer("cameramod_offscreen", w, h, true);
            }

            accessor.cameramod$setFramebuffer(offscreenFbo);
            mc.options.setPerspective(Perspective.FIRST_PERSON);
            mc.setCameraEntity(camera);

            // Save the player's cameraY / lastCameraY and restore the camera's
            // saved values.  This prevents the player's sneak eye-height offset
            // from leaking into the camera view.
            CameraAccessor camAccessor = (CameraAccessor) mc.gameRenderer.getCamera();
            float playerCameraY = camAccessor.cameramod$getCameraY();
            float playerLastCameraY = camAccessor.cameramod$getLastCameraY();
            if (!Float.isNaN(savedCamCameraY)) {
                camAccessor.cameramod$setCameraY(savedCamCameraY);
                camAccessor.cameramod$setLastCameraY(savedCamLastCameraY);
            }

            // Replace the built chunks list with ALL loaded chunk sections so the
            // camera can render chunks in any direction, not just the player's frustum.
            // setupTerrain is still skipped (via mixin) to prevent async corruption.
            WorldRendererAccessor wrAccessor = (WorldRendererAccessor) mc.worldRenderer;
            ObjectArrayList<ChunkBuilder.BuiltChunk> builtChunks = wrAccessor.cameramod$getBuiltChunks();
            ObjectArrayList<ChunkBuilder.BuiltChunk> nearbyChunks = wrAccessor.cameramod$getNearbyChunks();
            BuiltChunkStorage chunkStorage = wrAccessor.cameramod$getChunkStorage();

            // Save the player's chunk lists
            ChunkBuilder.BuiltChunk[] savedBuilt = builtChunks.toArray(new ChunkBuilder.BuiltChunk[0]);
            ChunkBuilder.BuiltChunk[] savedNearby = nearbyChunks.toArray(new ChunkBuilder.BuiltChunk[0]);

            // Fill with all chunk sections that have render data
            builtChunks.clear();
            nearbyChunks.clear();
            if (chunkStorage != null) {
                for (ChunkBuilder.BuiltChunk chunk : chunkStorage.chunks) {
                    if (chunk != null && chunk.getCurrentRenderData() != ChunkRenderData.HIDDEN) {
                        builtChunks.add(chunk);
                    }
                }
            }

            // Save fog state before camera pass (fogMultiplier is direction-dependent
            // and lerps over time — camera pass would corrupt it for the player's view)
            float savedFogMultiplier = 0f;
            AtmosphericFogModifierAccessor fogModAccess = null;
            for (FogModifier mod : FogRendererAccessor.cameramod$getFogModifiers()) {
                if (mod instanceof AtmosphericFogModifier) {
                    fogModAccess = (AtmosphericFogModifierAccessor) mod;
                    savedFogMultiplier = fogModAccess.cameramod$getFogMultiplier();
                    break;
                }
            }

            gameRenderer.renderWorld(tickCounter);

            // Restore fog state so player's render pass isn't affected
            if (fogModAccess != null) {
                fogModAccess.cameramod$setFogMultiplier(savedFogMultiplier);
            }

            // Re-dirty the lightmap so the player's renderWorld() recomputes it
            // (the camera pass consumed the dirty flag, leaving the player's pass
            // with a lightmap computed for the camera entity's position)
            ((LightmapTextureManagerAccessor) mc.gameRenderer.getLightmapTextureManager())
                    .cameramod$setDirty(true);

            // Restore the player's chunk lists
            builtChunks.clear();
            builtChunks.addElements(0, savedBuilt);
            nearbyChunks.clear();
            nearbyChunks.addElements(0, savedNearby);

            // Save camera's cameraY state for next frame
            savedCamCameraY = camAccessor.cameramod$getCameraY();
            savedCamLastCameraY = camAccessor.cameramod$getLastCameraY();
            // Restore the player's cameraY
            camAccessor.cameramod$setCameraY(playerCameraY);
            camAccessor.cameramod$setLastCameraY(playerLastCameraY);

            // Render chat overlay into the offscreen FBO if cameraSeesChat is enabled
            if (getGameruleBool(Cameramod.CAMERA_SEES_CHAT)) {
                renderChatOverlay(mc, gameRenderer, w, h);
            }

            // Check cameraFlipped gamerule via integrated server (or default false)
            boolean flipped = getGameruleBool(Cameramod.CAMERA_FLIPPED);
            captureFramebuffer(offscreenFbo, w, h, flipped);

        } catch (Exception e) {
            Cameramod.LOGGER.error("CameraRenderer second pass failed", e);
        } finally {
            accessor.cameramod$setFramebuffer(mainFbo);
            mc.setCameraEntity(savedCameraEntity);
            mc.options.setPerspective(savedPerspective);

            rendering = false;
        }
    }

    private static boolean getGameruleBool(GameRules.Key<GameRules.BooleanRule> key) {
        if (key == Cameramod.CAMERA_SEES_CHAT) return cameraSeesChatLocal != null ? cameraSeesChatLocal : cameraSeesChatSynced;
        if (key == Cameramod.CAMERA_FLIPPED) return cameraFlippedLocal != null ? cameraFlippedLocal : cameraFlippedSynced;
        return false;
    }

    /**
     * Find the real camera entity in the client world.
     * Returns null if the entity is not tracked (player too far away).
     */
    private static Entity findRealCamera(MinecraftClient mc) {
        if (boundCameraUuid == null) return null;

        for (Entity entity : mc.world.getEntities()) {
            if (entity.getUuid().equals(boundCameraUuid) && entity instanceof CameraEntity) {
                return entity;
            }
        }

        // Fall back to client-only cameras (no server mod or client-spawned)
        CameraEntity clientCam = CameramodClient.CLIENT_CAMERAS.get(boundCameraUuid);
        if (clientCam != null) return clientCam;

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

    private static void captureFramebuffer(Framebuffer framebuffer, int targetWidth, int targetHeight, boolean flipped) {
        GpuTexture gpuTexture = framebuffer.getColorAttachment();
        if (gpuTexture == null) return;

        // Use actual GPU texture dimensions for the buffer and stride calculation
        int texWidth = gpuTexture.getWidth(0);
        int texHeight = gpuTexture.getHeight(0);
        int pixelSize = gpuTexture.getFormat().pixelSize();

        GpuBuffer gpuBuffer = RenderSystem.getDevice().createBuffer(
                () -> "CameraRenderer buffer", 9,
                texWidth * texHeight * pixelSize
        );

        var encoder = RenderSystem.getDevice().createCommandEncoder();

        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
                gpuTexture, gpuBuffer, 0, () -> {
                    try (GpuBuffer.MappedView mappedView = encoder.mapBuffer(gpuBuffer, true, false)) {
                        ByteBuffer src = mappedView.data();
                        int srcStride = texWidth * pixelSize;

                        final int sw = targetWidth;
                        final int sh = targetHeight;
                        final int rowLen = sw * 3;
                        final int frameSize = rowLen * sh;

                        if (frameBytes == null || frameBytes.length != frameSize)
                            frameBytes = new byte[frameSize];
                        Arrays.fill(frameBytes, (byte) 0);

                        for (int y = 0; y < sh; y++) {
                            // Map target y to source y (nearest-neighbor scaling)
                            int srcY = y * texHeight / sh;
                            int srcRowOffset = srcY * srcStride;
                            int dstRow = (sh - 1 - y) * rowLen;
                            for (int x = 0; x < sw; x++) {
                                // Map target x to source x (nearest-neighbor scaling)
                                int srcX = x * texWidth / sw;
                                int si = srcRowOffset + srcX * pixelSize;
                                int outX = flipped ? (sw - 1 - x) : x;
                                int di = dstRow + outX * 3;
                                frameBytes[di]     = src.get(si + 2); // B
                                frameBytes[di + 1] = src.get(si + 1); // G
                                frameBytes[di + 2] = src.get(si);     // R
                            }
                        }

                        if (Cameramod.softcamCamera != null) {
                            SoftCam.sendFrame(Cameramod.softcamCamera, frameBytes);
                        }
                        CameraStreamServer.pushFrame(frameBytes, sw, sh);
                        // Save as frozen frame for when player goes too far
                        if (boundCameraUuid != null) {
                            frozenFrame = Arrays.copyOf(frameBytes, frameBytes.length);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        gpuBuffer.close();
                    }
                }, 0
        );
    }
}
