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
import dev.tggamesyt.cameramod.mixin.client.FogRendererAccessor;
import dev.tggamesyt.cameramod.mixin.client.AtmosphericFogModifierAccessor;
import dev.tggamesyt.cameramod.mixin.client.WorldRendererAccessor;
import net.minecraft.client.render.fog.AtmosphericFogModifier;
import net.minecraft.client.render.fog.FogModifier;
import net.minecraft.client.MinecraftClient;
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
import net.minecraft.client.gl.GlobalSettings;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.option.TextureFilteringMode;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.rule.GameRule;

import org.lwjgl.opengl.GL11;

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

    // Consecutive camera-pass failures while VR (Vivecraft) is active. The VR
    // camera render is best-effort (we can't test it here); if it keeps throwing
    // we stop attempting it and feed the off image instead of spamming exceptions
    // every frame. Reset to 0 whenever VR is off or a camera pass succeeds.
    private static int vrCameraPassFailures = 0;
    private static final int VR_CAMERA_PASS_MAX_FAILURES = 3;

    // In VR the camera pass points renderWorld at the camera entity WITHOUT
    // calling mc.setCameraEntity() — that runs GameRenderer.onCameraEntitySet,
    // which Vivecraft hooks to re-origin its VR head tracking. Writing the field
    // twice every frame made the headset view snap back the moment the player
    // turned to the side. Instead we override getCameraEntity() for the duration
    // of the pass (MinecraftClientCameraEntityMixin) and leave the real
    // cameraEntity field on the player, so Vivecraft never sees a change.
    private static volatile Entity cameraEntityOverride = null;
    public static Entity getCameraEntityOverride() { return cameraEntityOverride; }

    // Saved Camera cameraY/lastCameraY for the camera entity pass —
    // prevents the player's sneak bob from leaking into the camera view.
    private static float savedCamCameraY = Float.NaN;
    private static float savedCamLastCameraY = Float.NaN;
    // Wall-clock gate: send at most one frame per (1 / camframerate) seconds,
    // independent of the game's render FPS.
    private static long lastFrameSendNanos = 0L;
    private static boolean rendering = false;
    // Separate gate for SoftCam emission. The render gate runs at
    // max(stream, virtual); SoftCam is downsampled to virtualFps so we don't
    // burn JNI calls feeding a driver faster than it actually emits.
    private static long lastSoftCamSendNanos = 0L;

    // Active camera zoom level (set during render pass for FOV override)
    private static float activeZoomLevel = 1.0f;

    // Last good frame from the camera (used when player is too far to render terrain)
    private static byte[] frozenFrame = null;
    private static long cameraNotFoundSinceNanos = 0L;
    private static final long CAMERA_MISSING_TIMEOUT_NANOS = 3_000_000_000L;

    // Rate-limit logging of recurring renderPlayerScreenOverlay exceptions so
    // a transient panorama-not-ready race during startup doesn't fill the log
    // with the same stacktrace. Logs first occurrence in full, then suppresses
    // until the message changes.
    private static String lastPlayerOverlayErrorMsg = null;

    // Off image animation state. Loaded on a background thread (266 frames is too
    // much to decode on the render thread — that froze the game on every resource
    // reload). The render thread only ever reads the volatile fields; the loader
    // builds a fresh list and swaps it in atomically when done.
    private static volatile List<byte[]> offImageFrames;  // null = not loaded yet
    private static volatile int offImageFps = 20;         // default fps
    private static volatile boolean offImageLoaded = false;  // a load has completed
    private static volatile boolean offImageLoading = false; // a load is in progress
    private static double offImageFrameAccumulator = 0; // (unused; wall-clock timing now)

    public static boolean isRendering() {
        return rendering;
    }

    public static float getActiveZoomLevel() {
        return activeZoomLevel;
    }

    // Snap a camera entity's previous-tick pose to its current pose before the
    // camera pass. 1.21.11's Camera.update() positions/orients the view by
    // interpolating between the entity's previous-tick state (lastX/Y/Z,
    // lastYaw/lastPitch) and its current state by the frame's tick progress.
    // The per-frame fixer positions cameras with setPosition()/setYaw(), which
    // never touch those prev-tick fields, and client-only cameras aren't
    // tick-synced — so lastX/Y/Z stay at the camera's spawn-at-player value and
    // the rendered viewpoint gets dragged toward the player even though the
    // camera entity itself is in the right place. Collapsing prev==current makes
    // the lerp resolve to exactly where the camera is. (Pre-1.21.11 Camera.update
    // ran inside renderWorld and the camera ticked, so this wasn't needed.)
    private static void syncPrevPose(Entity e) {
        e.lastX = e.getX();
        e.lastY = e.getY();
        e.lastZ = e.getZ();
        e.lastYaw = e.getYaw();
        e.lastPitch = e.getPitch();
    }

    // Saved player camera position from just before the camera pass — used by
    // CloudRendererMixin to keep the cloud cell aligned to the player so the
    // cloud face buffer doesn't get rebuilt twice every frame (once for the
    // camera viewpoint, then again to restore the player viewpoint).
    private static Vec3d savedPlayerCameraPos = null;

    public static Vec3d getSavedPlayerCameraPos() {
        return savedPlayerCameraPos;
    }

    // ─── Per-viewpoint terrain visibility (vanilla) ──────────────────────────
    // The camera pass and player pass share WorldRenderer's occlusion graph
    // (chunkRenderingDataPreparer) and visible-chunk lists (builtChunks /
    // nearbyChunks). The camera's BFS is bounded by render distance from the
    // CAMERA, so it leaves chunks beyond that distance out of the shared graph;
    // the player pass doesn't fully restore them, so the ring of chunks beyond the
    // camera's render distance (but inside the player's) jitters in the player's
    // view. Giving the camera pass its OWN preparer + lists fully isolates the two
    // viewpoints' visibility computation. The chunk grid (and its built meshes) is
    // still shared — only the visibility bookkeeping is per-viewpoint.
    //
    // Disabled under Sodium (Sodium replaces this whole pipeline) and self-heals:
    // any failure leaves the shared state in place (vanilla's prior behaviour).
    private static final boolean VANILLA_TERRAIN = cameramod$detectVanillaTerrain();
    private static boolean cameramod$detectVanillaTerrain() {
        try {
            Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            return false; // Sodium present
        } catch (Throwable t) {
            return true;
        }
    }

    private static net.minecraft.client.render.ChunkRenderingDataPreparer camPreparer = null;
    private static it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.client.render.chunk.ChunkBuilder.BuiltChunk> camBuiltChunks = null;
    private static it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.client.render.chunk.ChunkBuilder.BuiltChunk> camNearbyChunks = null;
    private static net.minecraft.client.render.BuiltChunkStorage camPreparerGrid = null;
    private static net.minecraft.util.math.ChunkSectionPos camPreparerSectionPos = null;

    // Player state stashed for the duration of the camera pass.
    private static net.minecraft.client.render.ChunkRenderingDataPreparer plPreparer = null;
    private static it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.client.render.chunk.ChunkBuilder.BuiltChunk> plBuiltChunks = null;
    private static it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.client.render.chunk.ChunkBuilder.BuiltChunk> plNearbyChunks = null;
    private static boolean cameraTerrainActive = false;

    private static void swapInCameraTerrain(MinecraftClient mc) {
        if (!VANILLA_TERRAIN || mc.worldRenderer == null) return;
        try {
            WorldRendererAccessor wr = (WorldRendererAccessor) mc.worldRenderer;
            net.minecraft.client.render.BuiltChunkStorage grid = wr.cameramod$getChunkStorage();
            if (grid == null) return; // world not ready

            // (Re)create the camera's preparer when first used or when the grid
            // object itself changed (world (re)load). Binding it to the same grid
            // means it renders the SAME built chunk meshes, just with its own
            // visibility graph.
            if (camPreparer == null || camPreparerGrid != grid) {
                camPreparer = new net.minecraft.client.render.ChunkRenderingDataPreparer();
                camPreparer.setStorage(grid);
                camBuiltChunks = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
                camNearbyChunks = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
                camPreparerGrid = grid;
                camPreparerSectionPos = grid.getSectionPos();
            } else {
                // The player pass recenters the SHARED grid when the player crosses
                // a chunk; the camera octree must be rebuilt to match the new layout.
                // Do it via scheduleTerrainUpdate(), NOT setStorage().
                //
                // setStorage() immediately resets the preparer's `state` to a fresh
                // EMPTY PreparerState (empty octree) and only THEN kicks off the
                // rebuild — and that rebuild runs async on the main worker executor.
                // For every frame until it finishes, applyFrustum()→collectChunks()
                // walks the empty octree, so camBuiltChunks comes back empty and the
                // camera streams a blank world: the "camera flashes the world every
                // once in a while" bug, which correlates exactly with crossing chunk
                // boundaries (when the grid recenters).
                //
                // scheduleTerrainUpdate() just flags the preparer; the subsequent
                // updateTerrain()→method_52820 builds an entirely new octree off the
                // main thread and swaps it into `state` ATOMICALLY only once it's
                // complete. Until then the previous (very slightly stale) octree
                // stays in place, so the camera keeps rendering a full world with no
                // empty frame — the same way the player pass rides out a recenter.
                net.minecraft.util.math.ChunkSectionPos cur = grid.getSectionPos();
                if (cur != null && !cur.equals(camPreparerSectionPos)) {
                    camPreparer.scheduleTerrainUpdate();
                    camPreparerSectionPos = cur;
                }
            }

            plPreparer     = wr.cameramod$getChunkRenderingDataPreparer();
            plBuiltChunks  = wr.cameramod$getBuiltChunks();
            plNearbyChunks = wr.cameramod$getNearbyChunks();

            wr.cameramod$setChunkRenderingDataPreparer(camPreparer);
            wr.cameramod$setBuiltChunks(camBuiltChunks);
            wr.cameramod$setNearbyChunks(camNearbyChunks);
            cameraTerrainActive = true;
        } catch (Throwable t) {
            // Leave the shared state untouched (prior behaviour) on any failure.
            cameraTerrainActive = false;
        }
    }

    private static void swapOutCameraTerrain(MinecraftClient mc) {
        if (!cameraTerrainActive) return;
        cameraTerrainActive = false;
        if (mc.worldRenderer == null) return;
        try {
            WorldRendererAccessor wr = (WorldRendererAccessor) mc.worldRenderer;
            if (plPreparer != null) wr.cameramod$setChunkRenderingDataPreparer(plPreparer);
            if (plBuiltChunks != null) wr.cameramod$setBuiltChunks(plBuiltChunks);
            if (plNearbyChunks != null) wr.cameramod$setNearbyChunks(plNearbyChunks);
        } catch (Throwable ignored) {}
    }

    // ─── Per-viewpoint cloud geometry ────────────────────────────────────────
    // CloudRenderer keeps ONE built cloud-face buffer for the cell the camera is
    // in; a viewpoint in a different cell rebuilds it. Rendering the world twice
    // per frame from two cells would rebuild it twice every frame (stutter).
    // We give the camera pass its own cloud-face buffer + cell state: before the
    // camera renderWorld we save the player's cloud state and swap in the
    // camera's, and after it we save the camera's back and restore the player's.
    // Each viewpoint then rebuilds only when IT crosses a cell, and — crucially —
    // the camera renders clouds from its OWN position, so they stay world-fixed
    // instead of sliding around with the player.
    private static net.minecraft.client.gl.MappableRingBuffer camCloudFacesBuffer = null;
    private static int     camCloudCenterX = 0;
    private static int     camCloudCenterZ = 0;
    private static int     camCloudInstanceCount = 0;
    private static boolean camCloudRebuild = true; // force a build on first use
    private static boolean camCloudStateInitialized = false;

    // Player state stashed for the duration of the camera pass.
    private static net.minecraft.client.gl.MappableRingBuffer plCloudFacesBuffer = null;
    private static int     plCloudCenterX, plCloudCenterZ, plCloudInstanceCount;
    private static boolean plCloudRebuild;

    private static boolean cameraCloudStateActive = false;

    /** True while the camera-dedicated cloud buffer is swapped in (main pass). */
    public static boolean isCameraCloudStateActive() {
        return cameraCloudStateActive;
    }

    private static void swapInCameraCloudState(MinecraftClient mc) {
        if (mc.worldRenderer == null) return;
        net.minecraft.client.render.CloudRenderer cr =
                ((dev.tggamesyt.cameramod.mixin.client.WorldRendererAccessor) mc.worldRenderer)
                        .cameramod$getCloudRenderer();
        if (cr == null) return;
        dev.tggamesyt.cameramod.mixin.client.CloudRendererAccessor a =
                (dev.tggamesyt.cameramod.mixin.client.CloudRendererAccessor) cr;

        // Save the player's current cloud geometry/cell.
        plCloudFacesBuffer  = a.cameramod$getCloudFacesBuffer();
        plCloudCenterX      = a.cameramod$getCenterX();
        plCloudCenterZ      = a.cameramod$getCenterZ();
        plCloudInstanceCount = a.cameramod$getInstanceCount();
        plCloudRebuild      = a.cameramod$getRebuild();

        // First use: seed the camera's state from the player's so the buffer
        // sizing (cloud render distance) matches; force a rebuild for the camera
        // cell. After that we reuse the camera's own retained buffer.
        if (!camCloudStateInitialized) {
            camCloudFacesBuffer = null;       // null → CloudRenderer allocates one
            camCloudInstanceCount = 0;
            camCloudRebuild = true;
            camCloudStateInitialized = true;
        }

        // Swap in the camera's geometry/cell.
        a.cameramod$setCloudFacesBuffer(camCloudFacesBuffer);
        a.cameramod$setCenterX(camCloudCenterX);
        a.cameramod$setCenterZ(camCloudCenterZ);
        a.cameramod$setInstanceCount(camCloudInstanceCount);
        a.cameramod$setRebuild(camCloudRebuild);
        cameraCloudStateActive = true;
    }

    private static void swapOutCameraCloudState(MinecraftClient mc) {
        if (!cameraCloudStateActive) return;
        cameraCloudStateActive = false;
        if (mc.worldRenderer == null) return;
        net.minecraft.client.render.CloudRenderer cr =
                ((dev.tggamesyt.cameramod.mixin.client.WorldRendererAccessor) mc.worldRenderer)
                        .cameramod$getCloudRenderer();
        if (cr == null) return;
        dev.tggamesyt.cameramod.mixin.client.CloudRendererAccessor a =
                (dev.tggamesyt.cameramod.mixin.client.CloudRendererAccessor) cr;

        // Save the camera's (possibly just-rebuilt) geometry/cell for next frame.
        camCloudFacesBuffer  = a.cameramod$getCloudFacesBuffer();
        camCloudCenterX      = a.cameramod$getCenterX();
        camCloudCenterZ      = a.cameramod$getCenterZ();
        camCloudInstanceCount = a.cameramod$getInstanceCount();
        camCloudRebuild      = a.cameramod$getRebuild();

        // Restore the player's geometry/cell so the player pass doesn't rebuild.
        a.cameramod$setCloudFacesBuffer(plCloudFacesBuffer);
        a.cameramod$setCenterX(plCloudCenterX);
        a.cameramod$setCenterZ(plCloudCenterZ);
        a.cameramod$setInstanceCount(plCloudInstanceCount);
        a.cameramod$setRebuild(plCloudRebuild);

        // The per-frame cloud info UBO (color/offset) lives in a ring buffer that
        // BOTH passes write. Advance it so the player pass writes a fresh section
        // and can't clobber the one the camera's (deferred) cloud draw still
        // samples — same race, and same fix, as the fog ring buffer.
        try { cr.rotate(); } catch (Throwable ignored) {}
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

    /**
     * Mark the off-image for reload from the (possibly just-changed) resource
     * packs. Hooked to client resource reloads so toggling/reloading a pack swaps
     * the off animation without a game restart. The current frames keep playing
     * until the background reload swaps the new ones in — no freeze, no gap.
     */
    public static void invalidateOffImageCache() {
        offImageLoaded = false;
    }

    /**
     * Kick off a background load of the off-image if one is needed and not
     * already running. Returns immediately; the render thread never blocks on
     * decoding (266 animation frames froze the game when done inline).
     */
    private static void ensureOffImage() {
        if (offImageLoaded || offImageLoading) return;
        if (!SoftCam.isInitialized() || Cameramod.softcamCamera == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getResourceManager() == null) return;

        offImageLoading = true;
        Thread t = new Thread(() -> {
            try {
                loadOffImageFrames(mc);
            } catch (Throwable e) {
                Cameramod.LOGGER.error("Off-image background load failed", e);
            } finally {
                offImageLoaded = true;
                offImageLoading = false;
            }
        }, "Cameramod-OffImageLoader");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Runs on the background loader thread. Decodes the off-image frames into a
     * fresh list, then swaps it into the volatile fields in one go so the render
     * thread only ever sees a complete frame set (the old one until then).
     */
    private static void loadOffImageFrames(MinecraftClient mc) {
        List<byte[]> frames = null;
        int fps = 20;

        // Animated off image: off.count defines frame count.
        int count = readIntResource(mc, Identifier.of(Cameramod.MOD_ID, "textures/gui/off.count"), -1);
        if (count > 0) {
            fps = readIntResource(mc, Identifier.of(Cameramod.MOD_ID, "textures/gui/off.fps"), 20);
            if (fps < 1) fps = 1;

            List<byte[]> loaded = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                byte[] frame = loadImageToBGR(mc, Identifier.of(Cameramod.MOD_ID, "textures/gui/off_" + i + ".png"));
                if (frame != null) loaded.add(frame);
                else Cameramod.LOGGER.warn("Missing off image frame: off_{}.png", i);
            }
            if (!loaded.isEmpty()) {
                frames = loaded;
                Cameramod.LOGGER.info("Loaded {} off image frames at {} fps", loaded.size(), fps);
            } else {
                Cameramod.LOGGER.error("off.count={} but no off_N.png frames found, falling back to off.png", count);
            }
        }

        // No animation → static off.png.
        if (frames == null) {
            byte[] frame = loadImageToBGR(mc, Identifier.of(Cameramod.MOD_ID, "textures/gui/off.png"));
            if (frame != null) {
                frames = new ArrayList<>();
                frames.add(frame);
                fps = 1; // static, doesn't matter
            } else {
                Cameramod.LOGGER.error("Failed to load off.png");
            }
        }

        // Atomic-ish swap: publish fps before frames (sendOffImage reads frames last).
        if (frames != null) {
            offImageFps = fps;
            offImageFrames = frames;
        }
    }

    private static void sendOffImage() {
        ensureOffImage();
        // Snapshot the volatile list so a background reload swapping it mid-method
        // can't cause an inconsistent size/index read.
        List<byte[]> frames = offImageFrames;
        if (frames == null || frames.isEmpty()) return;

        byte[] frame;
        if (frames.size() == 1) {
            frame = frames.get(0);
        } else {
            // Pick the frame from WALL-CLOCK time so the animation always plays at
            // exactly off.fps, independent of how often this method is called (the
            // old frame-count accumulator drifted slow whenever the actual call
            // rate fell below max(stream, virtual) fps).
            int count = frames.size();
            int fps = offImageFps > 0 ? offImageFps : 1;
            long frameNum = (System.currentTimeMillis() * fps) / 1000L;
            int frameIndex = (int) (frameNum % count);
            if (frameIndex < 0) frameIndex += count;
            frame = frames.get(frameIndex);
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

    // Out-of-game (title screen / menus) streaming toggle. Kept SEPARATE from the
    // in-game streamingEnabled flag on purpose: in-game actions (binding a camera,
    // restoring a persisted camera with streaming=true, the activator item, …) set
    // streamingEnabled, and that state used to leak back to the title screen — so
    // disabling streaming on the title screen, entering a world, then leaving would
    // come back with streaming re-enabled. The menu context owns its own flag, only
    // ever changed by the title-screen F9 toggle, so it survives a world session.
    private static boolean menuStreamingEnabled = false;

    // Translucency-sort position the LAST camera pass left in WorldRenderer's
    // lastTranslucencySortCameraPos field. We swap this in before each camera
    // pass and read the result back out after, so the vanilla translucency sorter
    // sees only camera→camera motion during the camera pass (and player→player
    // during the player pass). Without the swap, the field alternates player↔camera
    // every frame, re-sorting shared water/glass twice per frame from opposite
    // viewpoints — visible flicker. Null until the first camera pass runs.
    private static net.minecraft.util.math.BlockPos camPassTranslucencyPos = null;

    // ─── Edit-camera preview state ───────────────────────────────────────────
    // Set by EditCameraScreen while it's open. When non-null, after the main
    // camera frame is captured we render the same camera three more times —
    // once per perspective — into a small preview FBO and stash the BGR bytes
    // for the GUI to display. Throttled below the main camera framerate to
    // keep the cost down.
    private static UUID         previewCameraUuid       = null;
    private static byte[]       previewFrameView        = null;
    private static byte[]       previewFrameFront       = null;
    private static byte[]       previewFrameBack        = null;
    private static int          previewFrameVersion     = 0;
    private static long         lastPreviewRenderNanos  = 0L;
    private static SimpleFramebuffer previewFbo         = null;
    public  static final int    PREVIEW_W               = 640;
    public  static final int    PREVIEW_H               = 360;
    private static final long   PREVIEW_INTERVAL_NANOS  = 200_000_000L; // 5 fps
    // Faster preview cadence while the user is dragging a field or rotating.
    // Capped (not unlimited): 3 extra world renders every frame would tank the
    // game framerate so hard that rotate-mode cursor handling visibly stutters.
    private static final long   PREVIEW_BOOST_INTERVAL_NANOS = 33_000_000L; // ~30 fps
    private static boolean      previewBoostMode        = false;
    private static boolean      viewCapturedFromMainPass = false;

    public static void setPreviewBoost(boolean boost) { previewBoostMode = boost; }

    public static void setPreviewCameraUuid(UUID uuid) {
        previewCameraUuid = uuid;
        if (uuid == null) {
            previewFrameView = previewFrameFront = previewFrameBack = null;
            previewFrameVersion = 0;
            if (previewFbo != null) {
                try { previewFbo.delete(); } catch (Throwable ignored) {}
                previewFbo = null;
            }
        }
    }
    public static UUID   getPreviewCameraUuid() { return previewCameraUuid; }
    public static byte[] getPreviewFrameView()  { return previewFrameView; }
    public static byte[] getPreviewFrameFront() { return previewFrameFront; }
    public static byte[] getPreviewFrameBack()  { return previewFrameBack; }
    public static int    getPreviewFrameVersion() { return previewFrameVersion; }

    // Gamerule values synced from server (type=3 cameraSeesChat, type=4 cameraFlipped,
    // type=5 cameraNameTags, type=6 cameraGuiMode, type=7 cameraShowPlayerGuis)
    private static boolean cameraSeesChatSynced = false;
    private static boolean cameraFlippedSynced = false;
    private static boolean cameraNameTagsSynced = true;
    private static boolean cameraGuiModeSynced = false;
    private static boolean cameraShowPlayerGuisSynced = false;
    private static int     streamFpsSynced     = 30;
    private static int     virtualFpsSynced    = 30;

    // Local client-side overrides (null = use server value)
    private static Boolean cameraSeesChatLocal = null;
    private static Boolean cameraFlippedLocal = null;
    private static Boolean cameraNameTagsLocal = null;
    private static Boolean cameraGuiModeLocal = null;
    private static Boolean cameraShowPlayerGuisLocal = null;
    private static Integer streamFpsLocal     = null;
    private static Integer virtualFpsLocal    = null;

    // Setting a client override also mirrors the value onto the matching server
    // game rule in a one-player singleplayer world (see syncGameRule*ToServer).
    // A null value means "clear override / use server value" — nothing to push.
    public static void setLocalSeesChat(Boolean val) {
        cameraSeesChatLocal = val;
        if (val != null) CameramodClient.syncGameRuleBoolToServer(Cameramod.CAMERA_SEES_CHAT, val);
    }
    public static void setLocalFlipped(Boolean val) {
        cameraFlippedLocal = val;
        if (val != null) CameramodClient.syncGameRuleBoolToServer(Cameramod.CAMERA_FLIPPED, val);
    }
    public static void setLocalNameTags(Boolean val) {
        cameraNameTagsLocal = val;
        if (val != null) CameramodClient.syncGameRuleBoolToServer(Cameramod.CAMERA_NAME_TAGS, val);
    }
    public static void setLocalGuiMode(Boolean val) {
        cameraGuiModeLocal = val;
        if (val != null) CameramodClient.syncGameRuleBoolToServer(Cameramod.CAMERA_GUI_MODE, val);
    }
    public static void setLocalShowPlayerGuis(Boolean val) {
        cameraShowPlayerGuisLocal = val;
        if (val != null) CameramodClient.syncGameRuleBoolToServer(Cameramod.CAMERA_SHOW_PLAYER_GUIS, val);
    }
    public static Boolean getLocalSeesChat() { return cameraSeesChatLocal; }
    public static Boolean getLocalFlipped() { return cameraFlippedLocal; }
    public static Boolean getLocalNameTags() { return cameraNameTagsLocal; }
    public static Boolean getLocalGuiMode() { return cameraGuiModeLocal; }
    public static Boolean getLocalShowPlayerGuis() { return cameraShowPlayerGuisLocal; }

    // Client-only toggle: when true, the 3D camera entity models are not drawn
    // for this client (the cameras still function — stream, attachment, fixer —
    // they're just visually hidden). Off by default. The entity renderer reads
    // this in shouldRender(); the attachment/fixer logic runs in
    // WorldRenderEvents.START independently, so hiding the model is purely cosmetic.
    private static boolean hideCameraModels = false;
    public static boolean isHideCameraModels() { return hideCameraModels; }
    public static void setHideCameraModels(boolean v) { hideCameraModels = v; }

    // Client-only toggle: when true, the floating name tags of camera entities are
    // not drawn in THIS player's normal view (the camera's own name still works in
    // the stream — that's governed by getCameraNameTags()). Off by default. Read by
    // EntityRendererLabelMixin when NOT in the camera pass.
    private static boolean hideCameraNameTagsForPlayers = false;
    public static boolean isHideCameraNameTagsForPlayers() { return hideCameraNameTagsForPlayers; }
    public static void setHideCameraNameTagsForPlayers(boolean v) { hideCameraNameTagsForPlayers = v; }

    public static void setLocalStreamFps(Integer val)  {
        streamFpsLocal = val == null ? null : Math.max(1, Math.min(240, val));
        if (streamFpsLocal != null) CameramodClient.syncGameRuleIntToServer(Cameramod.CAMERA_STREAM_FPS, streamFpsLocal);
    }
    public static void setLocalVirtualFps(Integer val) {
        virtualFpsLocal = val == null ? null : Math.max(1, Math.min(240, val));
        if (virtualFpsLocal != null) CameramodClient.syncGameRuleIntToServer(Cameramod.CAMERA_VIRTUAL_FPS, virtualFpsLocal);
    }
    public static Integer getLocalStreamFps()  { return streamFpsLocal; }
    public static Integer getLocalVirtualFps() { return virtualFpsLocal; }
    public static void    setStreamFpsSynced(int v)  { streamFpsSynced  = Math.max(1, Math.min(240, v)); }
    public static void    setVirtualFpsSynced(int v) { virtualFpsSynced = Math.max(1, Math.min(240, v)); }
    /** Effective max FPS for the localhost HTTP stream. */
    public static int     getStreamFps()  { return streamFpsLocal  != null ? streamFpsLocal  : streamFpsSynced;  }
    /** Effective max FPS for SoftCam (virtual webcam) sends. */
    public static int     getVirtualFps() { return virtualFpsLocal != null ? virtualFpsLocal : virtualFpsSynced; }

    public static boolean getCameraSeesChat() {
        UUID b = boundCameraUuid;
        if (b != null) { CameramodClient.TrackedCamera tc = CameramodClient.TRACKED_CAMERAS.get(b); if (tc != null && tc.perCamSeesChat != null) return tc.perCamSeesChat; }
        return cameraSeesChatLocal != null ? cameraSeesChatLocal : cameraSeesChatSynced;
    }
    public static boolean getCameraFlipped() {
        UUID b = boundCameraUuid;
        if (b != null) { CameramodClient.TrackedCamera tc = CameramodClient.TRACKED_CAMERAS.get(b); if (tc != null && tc.perCamFlipped != null) return tc.perCamFlipped; }
        return cameraFlippedLocal != null ? cameraFlippedLocal : cameraFlippedSynced;
    }
    // cameraGuiMode is a per-player setting (controls whether the viewing
    // player's HUD shows while looking through a camera), not a per-camera one
    // — no TrackedCamera override here.
    public static boolean getCameraGuiMode() {
        return cameraGuiModeLocal != null ? cameraGuiModeLocal : cameraGuiModeSynced;
    }
    public static boolean getCameraShowPlayerGuis() {
        UUID b = boundCameraUuid;
        if (b != null) { CameramodClient.TrackedCamera tc = CameramodClient.TRACKED_CAMERAS.get(b); if (tc != null && tc.perCamShowPlayerGuis != null) return tc.perCamShowPlayerGuis; }
        return cameraShowPlayerGuisLocal != null ? cameraShowPlayerGuisLocal : cameraShowPlayerGuisSynced;
    }

    public static UUID getBoundCameraUuid() {
        return boundCameraUuid;
    }

    public static void setBoundCamera(UUID cameraUuid) {
        boundCameraUuid = cameraUuid;
    }

    public static void clearBoundCamera() {
        boundCameraUuid = null;
        frozenFrame = null;
        cameraNotFoundSinceNanos = 0L;
    }

    public static void setStreamingEnabled(boolean enabled) {
        streamingEnabled = enabled;
    }

    public static boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public static void setMenuStreamingEnabled(boolean enabled) {
        menuStreamingEnabled = enabled;
    }

    public static boolean isMenuStreamingEnabled() {
        return menuStreamingEnabled;
    }

    public static void setCameraSeesChat(boolean value) {
        cameraSeesChatSynced = value;
    }

    public static void setCameraFlipped(boolean value) {
        cameraFlippedSynced = value;
    }

    public static void setCameraNameTags(boolean value) {
        cameraNameTagsSynced = value;
    }

    public static void setCameraGuiModeSynced(boolean value) {
        cameraGuiModeSynced = value;
    }

    public static void setCameraShowPlayerGuisSynced(boolean value) {
        cameraShowPlayerGuisSynced = value;
    }

    public static boolean getCameraNameTags() {
        UUID b = boundCameraUuid;
        if (b != null) { CameramodClient.TrackedCamera tc = CameramodClient.TRACKED_CAMERAS.get(b); if (tc != null && tc.perCamNameTags != null) return tc.perCamNameTags; }
        return cameraNameTagsLocal != null ? cameraNameTagsLocal : cameraNameTagsSynced;
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
        // In VR a flat player-POV grab isn't possible (stereo); skip capture.
        if (VivecraftCompat.isVrActive() || VivecraftCompat.isVivecraftTarget(mc.getFramebuffer())) return;

        boolean flipped = getGameruleBool(Cameramod.CAMERA_FLIPPED);
        captureFramebuffer(mc.getFramebuffer(), Cameramod.camwidth, Cameramod.camheight, flipped);
        try {
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        } catch (Throwable ignored) {}
    }

    public static void onFrameRendered(GameRenderer gameRenderer, RenderTickCounter tickCounter) {
        if (rendering) return;
        // Undo any Sodium synchronous-sort override left from a previous camera
        // pass. On frames that skip the camera pass this restores normal async
        // sorting; on frames that do render, beforeCameraPass() re-applies it
        // right before the camera renderWorld. No-op without Sodium.
        SodiumTranslucencyCompat.restore();
        boolean hasSoftCam = SoftCam.isInitialized() && Cameramod.softcamCamera != null;
        boolean hasStreamConsumers = hasSoftCam || CameraStreamServer.hasClients();
        boolean hasPreviewTarget = previewCameraUuid != null;
        if (!hasStreamConsumers && !hasPreviewTarget) return;

        MinecraftClient mc = MinecraftClient.getInstance();

        // Independent caps for the two consumers — HTTP isn't bound by the
        // virtual cam's driver rate. Render at the higher of the two; SoftCam
        // sends are then downsampled by their own gate (in captureFramebuffer).
        int streamFps  = getStreamFps();
        int virtualFps = getVirtualFps();
        int renderRate = Math.max(streamFps, virtualFps);
        if (renderRate <= 0) return;
        long now = System.nanoTime();
        long intervalNanos = 1_000_000_000L / renderRate;
        if (now - lastFrameSendNanos < intervalNanos) return;
        lastFrameSendNanos = now;

        // Is VR on this session? Use the SESSION flag (isVrMode), not the per-eye
        // VR_RUNNING flag: on the 1.21.9+ frame-graph pipeline our camera pass runs
        // during the vanilla desktop GameRenderer.render where VR_RUNNING is false
        // but Vivecraft's multi-pass target is still installed. Keying the mono
        // levers + getCameraEntity override off VR_RUNNING there meant neither was
        // applied — the camera renderWorld hit Vivecraft's MultiPassTextureTarget
        // with no VANILLA lever and NPE-crashed. In VR we render the camera POV into
        // our OWN offscreen FBO (mono levers drop Vivecraft to its vanilla path);
        // the player-POV capture path can't grab a flat VR framebuffer and falls
        // back to the off image. No-op without Vivecraft.
        boolean vrActive = VivecraftCompat.isVrMode();
        if (!vrActive) vrCameraPassFailures = 0; // forget failures once VR is off

        if (mc.world == null || mc.player == null) {
            if (hasStreamConsumers) {
                // Out-of-game (title screen, server list, "Connecting…"): only
                // stream when the user has toggled streaming on via F9. When on,
                // capture the REAL window framebuffer at the end of the frame
                // (capturePlayerPov) instead of re-rendering the screen into an
                // offscreen FBO — re-rendering the title/menu panorama corrupts
                // the live window view and crashes on not-yet-initialised cube
                // maps. Off → feed the off-image so the stream shows "off".
                // Uses the menu-only toggle so in-game streaming state can't leak
                // here (see menuStreamingEnabled). In VR there's no flat window
                // framebuffer to grab, so fall back to the off image.
                if (menuStreamingEnabled && !vrActive) capturePlayerPov = true;
                else                                    sendOffImage();
            }
            return;
        }
        if (CameramodClient.isCamera) return;

        // Decide whether the main stream pass will actually run this frame.
        // When it won't, but EditCameraScreen has registered a previewCameraUuid,
        // we still drop into a preview-only render so the View/Front/Back
        // images and the camera-list thumbnail are populated for cameras that
        // were never bound.
        boolean wantMainPass = hasStreamConsumers && streamingEnabled && boundCameraUuid != null;
        Entity camera = null;
        if (wantMainPass) {
            camera = findRealCamera(mc);
            if (camera == null) {
                if (cameraNotFoundSinceNanos == 0L) cameraNotFoundSinceNanos = now;
                boolean timedOut = (now - cameraNotFoundSinceNanos) >= CAMERA_MISSING_TIMEOUT_NANOS;
                if (frozenFrame != null && !timedOut) {
                    if (hasSoftCam) SoftCam.sendFrame(Cameramod.softcamCamera, frozenFrame);
                    CameraStreamServer.pushFrame(frozenFrame, Cameramod.camwidth, Cameramod.camheight);
                } else {
                    sendOffImage();
                }
                wantMainPass = false;
            } else {
                cameraNotFoundSinceNanos = 0L;
            }
        } else if (hasStreamConsumers) {
            // Streaming disabled or no bound camera → keep the consumers fed
            // with the off image / queue the player POV capture as before.
            // The player-POV grab reads the window framebuffer, which in VR is
            // Vivecraft's stereo target — not capturable — so use the off image.
            if (!streamingEnabled || vrActive) sendOffImage();
            else                               capturePlayerPov = true;
        }

        // VR camera render kept failing earlier this VR session — stop retrying
        // (it was spamming exceptions and lagging) and just feed the off image
        // until VR is turned off (the counter resets then).
        if (wantMainPass && vrActive && vrCameraPassFailures >= VR_CAMERA_PASS_MAX_FAILURES) {
            if (hasStreamConsumers) sendOffImage();
            wantMainPass = false;
        }

        if (!wantMainPass) {
            // renderEditPreviews is VR-safe now (mono levers + getCameraEntity
            // override), so the edit-screen previews render in VR too.
            if (hasPreviewTarget) {
                runPreviewOnly(mc, gameRenderer, tickCounter, now);
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

        // Suspend tr7zw's EntityCulling for the camera render. Its occlusion cull
        // flag is computed from the PLAYER's viewpoint on a background thread, so
        // the camera pass — which reads that same shared flag — drops entities the
        // camera can see but the player can't, and the async updates make them
        // flicker/jitter in the stream. Disabling it here lets the camera render
        // every frustum-visible entity; endCameraWork() in the finally re-enables
        // it (and restores its F3 counters) before the player pass. No-op without
        // EntityCulling.
        EntityCullingCompat.beginCameraWork();
        // Same idea for Sodium's OWN (section-visibility-based) entity culling,
        // whose visible-section set is rebuilt asynchronously per viewpoint and so
        // also flickers entities in the camera stream. No-op without Sodium.
        SodiumEntityCullingCompat.beginCameraWork();

        // Snapshot the player's render-camera position BEFORE we hand the Camera
        // object over to the camera entity. CloudRendererMixin substitutes this
        // for the cameraPos arg during the camera pass so CloudRenderer keeps
        // its centerX/centerZ aligned to the player's cell.
        savedPlayerCameraPos = mc.gameRenderer.getCamera().getCameraPos();

        Entity savedCameraEntity = mc.getCameraEntity();
        MinecraftClientAccessor accessor = (MinecraftClientAccessor) mc;
        Framebuffer mainFbo = accessor.cameramod$getFramebuffer();
        Perspective savedPerspective = mc.options.getPerspective();

        try {
            int w = Cameramod.camwidth;
            int h = Cameramod.camheight;
            // Cap the offscreen FBO at the configured camera resolution. Anything
            // we render above camwidth×camheight gets downsampled by nearest-
            // neighbor in captureFramebuffer — wasted GPU work AND a source of
            // sub-pixel edge jitter as the source aliases against the smaller
            // output grid each frame. At fullscreen 4K, sizing the FBO to the
            // window framebuffer was making the camera pass cost as much as the
            // full second world render, hard-capping FPS to ~20 with Sodium.
            // Use the largest camera-aspect rectangle that fits within both the
            // camera resolution AND the main FBO (so we never render bigger than
            // needed and never overflow the host window's framebuffer). In VR the
            // main FBO is Vivecraft's stereo target whose dimensions aren't a
            // usable flat size, so cap against the desktop window instead.
            int mainW = mainFbo.textureWidth;
            int mainH = mainFbo.textureHeight;
            if (vrActive || mainW <= 0 || mainH <= 0) {
                mainW = mc.getWindow().getFramebufferWidth();
                mainH = mc.getWindow().getFramebufferHeight();
            }
            int maxW = Math.min(w, mainW);
            int maxH = Math.min(h, mainH);
            float camAspect = (float) w / (float) h;
            float maxAspect = (float) maxW / (float) Math.max(1, maxH);
            int fbW, fbH;
            if (maxAspect > camAspect) {
                fbH = Math.max(1, maxH);
                fbW = Math.max(1, (int) (fbH * camAspect));
            } else {
                fbW = Math.max(1, maxW);
                fbH = Math.max(1, (int) (fbW / camAspect));
            }
            if (offscreenFbo == null || offscreenFbo.textureWidth != fbW || offscreenFbo.textureHeight != fbH) {
                if (offscreenFbo != null) offscreenFbo.delete();
                offscreenFbo = new SimpleFramebuffer("cameramod_offscreen", fbW, fbH, true);
            }

            accessor.cameramod$setFramebuffer(offscreenFbo);
            mc.options.setPerspective(Perspective.FIRST_PERSON);
            // In VR, override the getter instead of writing the field (see
            // cameraEntityOverride) so Vivecraft's onCameraEntitySet re-origin
            // hook never fires. In flat play the plain field swap is fine.
            if (vrActive) {
                cameraEntityOverride = camera;
            } else {
                mc.setCameraEntity(camera);
            }

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

            // Terrain / chunk visibility is rebuilt by setupTerrain which runs for
            // each viewpoint: once for the camera pass below, and again for the
            // player when MC's own renderWorld() runs afterwards. No manual chunk
            // list manipulation here — that workaround dropped any chunks the
            // player wasn't looking at when an FPS-optimizing mod replaced MC's
            // visibility cache (Sodium, Embeddium, Iris, …).
            //
            // We deliberately do NOT save/restore WorldRenderer.cameraChunkX/Y/Z
            // or lastCameraX/Y/Z. Doing so causes the player's setupTerrain to skip
            // its chunk-changed branch (which calls applyFrustum and rebuilds the
            // visible chunk list) when the player happens to be in the same chunk
            // as the previous frame — leaving builtChunks populated from the camera
            // pass's applyFrustum call, which uses the camera's frustum. Rendering
            // that chunk visibility set with the player's matrices causes massive
            // jitter on chunks the player can see but the camera can't.

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

            // Per-viewpoint translucency-sort position swap. WorldRenderer.render()
            // re-sorts translucent faces (water, glass) whenever the camera's block
            // pos differs from lastTranslucencySortCameraPos. That single field is
            // shared between the camera pass and the player pass, so left alone it
            // ping-pongs player↔camera every frame and shared water/glass re-sorts
            // twice per frame from opposite viewpoints — the visible flicker.
            // Swap in the position the LAST camera pass left, so this camera pass
            // sees only camera→camera motion (re-sorts at most once, when the camera
            // actually moves). Capture the camera pass's result and restore the
            // player's value so the player pass likewise sees only player→player
            // motion. (Generic vanilla path; engines with their own translucency
            // sorter — e.g. Sodium — keep their own field and are unaffected here.)
            WorldRendererAccessor wrAccess = (WorldRendererAccessor) mc.worldRenderer;
            net.minecraft.util.math.BlockPos savedTranslucencyPos =
                    wrAccess.cameramod$getLastTranslucencySortCameraPos();
            if (camPassTranslucencyPos != null) {
                wrAccess.cameramod$setLastTranslucencySortCameraPos(camPassTranslucencyPos);
            }

            // Give the camera pass its OWN occlusion/visible-chunk graph + visible
            // lists so its viewpoint-bounded BFS never corrupts the player's
            // visible-chunk set (that shared corruption is what made chunks beyond
            // the camera's render distance jitter in the player's view). Vanilla
            // only; restored in swapOutCameraTerrain / the finally. Must happen
            // BEFORE scheduleTerrainUpdate so that schedules the camera's preparer.
            swapInCameraTerrain(mc);

            // Force a fresh setupTerrain for the camera pass. Optimization mods
            // (Sodium, Embeddium, Iris, …) cache their own section-visibility set.
            // Without this, they may skip their rebuild and start the camera pass
            // from the player's cached visibility — missing sections the camera
            // can see but the player can't (black/void areas in the stream).
            mc.worldRenderer.scheduleTerrainUpdate();
            // Sodium-family engines keep ONE shared translucent index buffer per
            // section and re-sort it asynchronously, so the camera and player
            // passes fight over a single sort order (one view always shows the
            // other's). beforeCameraPass() flips Sodium's sort to its synchronous
            // (zero-frame-defer) mode for the duration of both passes: each pass's
            // own setupTerrain then re-sorts the differing sections for its own
            // viewpoint and uploads BEFORE it draws, so both views are correct.
            // No-op when Sodium isn't present. (restore() in onFrameRendered undoes
            // it next frame.)
            // Swap in the camera's own cloud geometry so the camera renders
            // clouds from its real position (world-fixed) without rebuilding the
            // player's cloud buffer. swapOut (below) restores the player's and
            // advances the per-frame cloud info ring so the player pass can't
            // clobber the camera's deferred cloud draw.
            swapInCameraCloudState(mc);

            // In VR, drop out of Vivecraft's stereo render for this whole pass so
            // updateCamera()/renderWorld draw a flat mono view from the camera
            // entity into our offscreen FBO instead of applying the VR head pose
            // (restored in the finally before the player's real VR render, which
            // is injected after this pass). No-op without Vivecraft.
            boolean vrMono = vrActive && VivecraftCompat.beginMonoRender();
            try {
                SodiumTranslucencyCompat.beforeCameraPass();
                // 1.21.11 moved Camera.update() out of renderWorld() into the separate
                // updateCamera(), which render() only calls AFTER our camera pass (we
                // inject at render HEAD). Without this the Camera still holds the
                // player's position and the stream renders from the player's POV.
                // Reposition it onto the camera entity for the (FIRST_PERSON) pass.
                syncPrevPose(camera);
                gameRenderer.updateCamera(tickCounter);
                // GlobalSettings holds the camera-position GPU UBO consumed by chunk
                // shaders to compute (chunkOrigin - cameraPos). GameRenderer.render()
                // normally calls this at offset 232, AFTER our HEAD injection returns,
                // so it hasn't run yet this frame. Without it the UBO still contains
                // the previous frame's player position and chunks appear at the player
                // instead of the camera entity.
                gameRenderer.getGlobalSettings().set(
                    mc.getWindow().getFramebufferWidth(),
                    mc.getWindow().getFramebufferHeight(),
                    mc.options.getGlintStrength().getValue(),
                    mc.world != null ? mc.world.getTime() : 0L,
                    tickCounter,
                    mc.options.getMenuBackgroundBlurrinessValue(),
                    gameRenderer.getCamera(),
                    mc.options.getTextureFiltering().getValue() == TextureFilteringMode.RGSS
                );
                gameRenderer.renderWorld(tickCounter);
                SodiumTranslucencyCompat.afterCameraPass();
            } finally {
                if (vrMono) VivecraftCompat.endMonoRender();
            }

            swapOutCameraCloudState(mc);
            swapOutCameraTerrain(mc);

            // Advance the fog GPU ring buffer so the upcoming player pass writes
            // its fog UBO to a DIFFERENT section than the camera pass just used.
            //
            // FogRenderer keeps the world-fog parameters (incl. the dense blue
            // water-fog when submerged) in a triple-buffered GpuBuffer ring, and
            // GameRenderer.render() rotates it only ONCE per frame — after the
            // player's renderWorld. Our camera pass runs first (injected at
            // render() HEAD), so without this both passes bind the same ring
            // section: the player pass overwrites it with the player's fog, and
            // because our offscreen capture's GPU work is deferred (async
            // copyTextureToBuffer), the camera's sky/world draws can read the
            // player's water fog at execution time — the camera stream flashes
            // underwater-blue for a few frames whenever the PLAYER enters water,
            // even with the camera on dry land. Rotating here gives the camera
            // its own section, left untouched until its draws complete. The ring
            // is fence-guarded (getBlocking waits), so the extra rotation/frame is
            // safe. Generic vanilla path — no rendering-mod assumptions.
            try {
                ((GameRendererAccessor) gameRenderer).cameramod$getFogRenderer().rotate();
            } catch (Throwable ignored) {}

            // Remember where the camera pass left the sort position (for next frame's
            // camera pass) and restore the player's value so the player pass sees
            // only player→player motion.
            camPassTranslucencyPos = wrAccess.cameramod$getLastTranslucencySortCameraPos();
            wrAccess.cameramod$setLastTranslucencySortCameraPos(savedTranslucencyPos);

            // NOTE (water-flicker isolation test): the post-camera-pass
            // scheduleTerrainUpdate() was REMOVED here. Under Sodium it forced a
            // full graph rebuild every frame from the player viewpoint right after
            // the camera-viewpoint rebuild, and Sodium's GFNI translucency sorter
            // re-sorts water along the camera-movement vector on each rebuild — the
            // suspected cause of the water flicker. The pre-camera-pass call above
            // is kept (needed so the camera pass builds from the camera viewpoint).
            // If entity/block-entity jitter returns without this, we'll reinstate a
            // gated version.

            // Restore fog state so player's render pass isn't affected
            if (fogModAccess != null) {
                fogModAccess.cameramod$setFogMultiplier(savedFogMultiplier);
            }

            // Re-dirty the lightmap so the player's renderWorld() recomputes it
            // (the camera pass consumed the dirty flag, leaving the player's pass
            // with a lightmap computed for the camera entity's position)
            ((LightmapTextureManagerAccessor) mc.gameRenderer.getLightmapTextureManager())
                    .cameramod$setDirty(true);

            // Save camera's cameraY state for next frame
            savedCamCameraY = camAccessor.cameramod$getCameraY();
            savedCamLastCameraY = camAccessor.cameramod$getLastCameraY();
            // Restore the player's cameraY
            camAccessor.cameramod$setCameraY(playerCameraY);
            camAccessor.cameramod$setLastCameraY(playerLastCameraY);

            // Render chat overlay into the offscreen FBO if cameraSeesChat is enabled
            if (getCameraSeesChat()) {
                renderChatOverlay(mc, gameRenderer, offscreenFbo.textureWidth, offscreenFbo.textureHeight);
            }
            // Render the player's currently-open screen (inventory, container, pause
            // menu, modded overlays…) into the camera FBO so Virtual Camera consumers see
            // it. Skipped if no screen is open, the gamerule is off, or the open
            // screen is one of our own GUIs — otherwise the camera card preview
            // would feed itself back through the overlay (visual feedback loop).
            // Also skipped for the chat screen when cameraSeesChat is on: the chat
            // overlay above already drew the chat, and the two render at different
            // scales so drawing both overlaps into an ugly mess.
            if (getCameraShowPlayerGuis() && mc.currentScreen != null
                    && !(mc.currentScreen instanceof dev.tggamesyt.cameramod.client.gui.CameraGuiScreen)
                    && !(mc.currentScreen instanceof dev.tggamesyt.cameramod.client.gui.EditCameraScreen)
                    && !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen
                         && getCameraSeesChat())) {
                renderPlayerScreenOverlay(mc, gameRenderer, offscreenFbo.textureWidth, offscreenFbo.textureHeight);
            }

            // cameraFlipped: per-camera override falls back to the gamerule value.
            boolean flipped = getCameraFlipped();
            captureFramebuffer(offscreenFbo, w, h, flipped);

            // ─── EditCameraScreen previews ─────────────────────────────
            // If the camera being previewed in EditCameraScreen IS the one we
            // just streamed, reuse the offscreen FBO for the "View" panel at
            // preview resolution — no need to re-render the world a 4th time.
            viewCapturedFromMainPass = false;
            if (hasPreviewTarget && previewCameraUuid != null
                    && previewCameraUuid.equals(boundCameraUuid)) {
                UUID pu = previewCameraUuid;
                boolean previewFlipped = flipped;
                capturePreviewFramebuffer(offscreenFbo, previewFlipped, bytes -> {
                    previewFrameView = bytes;
                    CameramodClient.updateTrackedFrame(pu, bytes, PREVIEW_W, PREVIEW_H);
                });
                viewCapturedFromMainPass = true;
                previewFrameVersion++;
            }
            renderEditPreviews(mc, gameRenderer, tickCounter, now);

            // Camera pass completed cleanly — if we're in VR, the best-effort
            // render is working, so clear the failure count.
            if (vrActive) vrCameraPassFailures = 0;

        } catch (Exception e) {
            Cameramod.LOGGER.error("CameraRenderer second pass failed", e);
            // In VR, count failures so we stop retrying (and stop lagging) after
            // a few and fall back to the off image until VR is turned off.
            if (vrActive) vrCameraPassFailures++;
        } finally {
            // Re-enable EntityCulling and restore its debug counters before the
            // player pass runs (later in GameRenderer.render).
            EntityCullingCompat.endCameraWork();
            SodiumEntityCullingCompat.endCameraWork();
            // Safety net: if renderWorld threw, the inline swapOut was skipped and
            // the camera's cloud buffer / terrain state is still swapped in. These
            // restore the player's (no-op if already restored).
            swapOutCameraCloudState(mc);
            swapOutCameraTerrain(mc);

            accessor.cameramod$setFramebuffer(mainFbo);
            // Clear the VR getter override / restore the flat-play field swap.
            if (vrActive) {
                cameraEntityOverride = null;
            } else {
                mc.setCameraEntity(savedCameraEntity);
            }
            mc.options.setPerspective(savedPerspective);

            // Reset pixel-pack state to GL defaults. MC's new render-device
            // copyTextureToBuffer pipeline (and other paths inside renderWorld)
            // may leave GL_PACK_ROW_LENGTH or alignment in a non-default state.
            // Downstream glGetTexImage callers (e.g. mchromium snapshotPixels in
            // CLIENT_TICK_END) assume defaults — a non-zero GL_PACK_ROW_LENGTH
            // makes the driver write past the end of their ByteBuffer and the
            // NVIDIA driver crashes with an access violation in nvoglv64.dll.
            try {
                GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            } catch (Throwable ignored) {}

            rendering = false;
        }
    }

    private static boolean getGameruleBool(GameRule<Boolean> key) {
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

    private static void renderMenuScreen(MinecraftClient mc, GameRenderer gameRenderer) {
        try {
            MinecraftClientAccessor accessor = (MinecraftClientAccessor) mc;
            Framebuffer mainFbo = accessor.cameramod$getFramebuffer();

            int w = Cameramod.camwidth;
            int h = Cameramod.camheight;
            int maxW = Math.min(w, mainFbo.textureWidth);
            int maxH = Math.min(h, mainFbo.textureHeight);
            float camAspect = (float) w / (float) h;
            float maxAspect = (float) maxW / (float) Math.max(1, maxH);
            int fbW, fbH;
            if (maxAspect > camAspect) {
                fbH = Math.max(1, maxH);
                fbW = Math.max(1, (int) (fbH * camAspect));
            } else {
                fbW = Math.max(1, maxW);
                fbH = Math.max(1, (int) (fbW / camAspect));
            }
            if (offscreenFbo == null || offscreenFbo.textureWidth != fbW || offscreenFbo.textureHeight != fbH) {
                if (offscreenFbo != null) offscreenFbo.delete();
                offscreenFbo = new SimpleFramebuffer("cameramod_offscreen", fbW, fbH, true);
            }

            accessor.cameramod$setFramebuffer(offscreenFbo);
            try {
                renderPlayerScreenOverlay(mc, gameRenderer, fbW, fbH);
                captureFramebuffer(offscreenFbo, w, h, getCameraFlipped());
            } finally {
                accessor.cameramod$setFramebuffer(mainFbo);
            }
        } catch (Exception e) {
            Cameramod.LOGGER.error("Failed to render menu screen for camera", e);
            sendOffImage();
        }
    }

    private static void renderPlayerScreenOverlay(MinecraftClient mc, GameRenderer gameRenderer, int fbWidth, int fbHeight) {
        try {
            GameRendererAccessor grAccessor = (GameRendererAccessor) gameRenderer;
            GuiRenderState guiState = grAccessor.cameramod$getGuiState();
            GuiRenderer guiRenderer = grAccessor.cameramod$getGuiRenderer();
            FogRenderer fogRenderer = grAccessor.cameramod$getFogRenderer();

            guiState.clear();
            float scaleFactor = (float) mc.getWindow().getScaleFactor();
            int   playerScaledW = mc.getWindow().getScaledWidth();
            int   playerScaledH = mc.getWindow().getScaledHeight();
            DrawContext drawContext = new DrawContext(mc, guiState, playerScaledW, playerScaledH);

            // Render at native (1:1 pixel) scale — no stretching.  The GUI
            // projection maps [0, playerScaledW] → NDC [-1,1], so without
            // correction it would fill the FBO (stretch).  Applying a scale of
            // (nativeW / fbWidth) undoes that implicit stretch and renders each
            // GUI unit as exactly one physical pixel (at the window's GUI scale).
            // A centering translation is added so the player's screen center
            // lands at the FBO center; anything outside the FBO clips naturally.
            float nativeScaleX = fbWidth  > 0 ? (float) mc.getWindow().getFramebufferWidth()  / fbWidth  : 1.0f;
            float nativeScaleY = fbHeight > 0 ? (float) mc.getWindow().getFramebufferHeight() / fbHeight : 1.0f;
            float offsetX = playerScaledW / 2.0f * (1.0f - nativeScaleX);
            float offsetY = playerScaledH / 2.0f * (1.0f - nativeScaleY);

            double mx = mc.mouse.getX() / scaleFactor;
            double my = mc.mouse.getY() / scaleFactor;

            net.minecraft.client.gui.screen.Screen screen = mc.currentScreen;
            float tickProgress = mc.getRenderTickCounter().getTickProgress(false);
            drawContext.getMatrices().pushMatrix();
            drawContext.getMatrices().translate(offsetX, offsetY);
            drawContext.getMatrices().scale(nativeScaleX, nativeScaleY);
            screen.renderWithTooltip(drawContext, (int) mx, (int) my, tickProgress);
            drawContext.getMatrices().popMatrix();

            guiRenderer.render(fogRenderer.getFogBuffer(FogRenderer.FogType.NONE));
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (!msg.equals(lastPlayerOverlayErrorMsg)) {
                Cameramod.LOGGER.error("Failed to render player screen overlay for camera", e);
                lastPlayerOverlayErrorMsg = msg;
            }
        }
    }

    private static void renderChatOverlay(MinecraftClient mc, GameRenderer gameRenderer, int fbWidth, int fbHeight) {
        try {
            GameRendererAccessor grAccessor = (GameRendererAccessor) gameRenderer;
            GuiRenderState guiState = grAccessor.cameramod$getGuiState();
            GuiRenderer guiRenderer = grAccessor.cameramod$getGuiRenderer();
            FogRenderer fogRenderer = grAccessor.cameramod$getFogRenderer();

            // Clear GUI state and create fresh DrawContext
            guiState.clear();
            float scaleFactor = (float) mc.getWindow().getScaleFactor();
            int scaledWidth = (int) (fbWidth / scaleFactor);
            int scaledHeight = (int) (fbHeight / scaleFactor);
            DrawContext drawContext = new DrawContext(mc, guiState, scaledWidth, scaledHeight);

            // Render chat into the GUI state
            ChatHud chatHud = mc.inGameHud.getChatHud();
            chatHud.render(drawContext, mc.textRenderer, mc.inGameHud.getTicks(), scaledWidth / 2, scaledHeight, false, false);

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

                        // All Java heap allocations first so GC can run freely,
                        // then SoftCam.sendFrame last because Memory.write() holds
                        // GCLocker (via GetPrimitiveArrayCritical) for the 6MB copy.
                        // Allocating after acquiring GCLocker triggers "Retried waiting
                        // for GCLocker too often" OOM on the render thread.
                        CameraStreamServer.pushFrame(frameBytes, sw, sh);
                        if (boundCameraUuid != null) {
                            if (frozenFrame == null || frozenFrame.length != frameBytes.length)
                                frozenFrame = new byte[frameBytes.length];
                            System.arraycopy(frameBytes, 0, frozenFrame, 0, frameBytes.length);
                            CameramodClient.updateTrackedFrame(boundCameraUuid, frameBytes, sw, sh);
                        }
                        if (Cameramod.softcamCamera != null) {
                            int vfps = getVirtualFps();
                            long vInterval = vfps > 0 ? 1_000_000_000L / vfps : Long.MAX_VALUE;
                            long nowNanos = System.nanoTime();
                            if (nowNanos - lastSoftCamSendNanos >= vInterval) {
                                lastSoftCamSendNanos = nowNanos;
                                SoftCam.sendFrame(Cameramod.softcamCamera, frameBytes);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        gpuBuffer.close();
                    }
                }, 0
        );
    }

    // ─── EditCameraScreen preview render path ───────────────────────────────
    // Renders the camera that EditCameraScreen has registered, three times,
    // at small resolution, once per perspective. Output bytes land in the
    // preview*Frame fields; EditCameraScreen reads them via the getters.
    // Runs inside the main onFrameRendered try-block so the outer finally
    // takes care of restoring camera entity / perspective / framebuffer.

    private static Entity findCameraEntityByUuid(MinecraftClient mc, UUID uuid) {
        if (uuid == null || mc.world == null) return null;
        for (Entity e : mc.world.getEntities()) {
            if (e.getUuid().equals(uuid) && e instanceof CameraEntity) return e;
        }
        CameraEntity client = CameramodClient.CLIENT_CAMERAS.get(uuid);
        return client;
    }

    /**
     * Preview-only render pass for the camera that EditCameraScreen is showing,
     * used when the main stream pass isn't running (no SoftCam clients, no
     * bound camera, streaming disabled, …).  Sets up the same per-pass state
     * (rendering flag, framebuffer, camera entity, perspective) as the main
     * path, then defers to renderEditPreviews — which contains the real work
     * and its own rate gate.
     */
    private static void runPreviewOnly(MinecraftClient mc, GameRenderer gameRenderer,
                                       RenderTickCounter tickCounter, long now) {
        if (previewCameraUuid == null) return;
        Entity previewCam = findCameraEntityByUuid(mc, previewCameraUuid);
        if (previewCam == null) return;

        rendering = true;
        // Same EntityCulling suspend as the main pass — the preview render also
        // draws the world from the camera's viewpoint. Restored in the finally.
        EntityCullingCompat.beginCameraWork();
        SodiumEntityCullingCompat.beginCameraWork();
        viewCapturedFromMainPass = false;
        // In VR renderEditPreviews drives the camera via the getCameraEntity
        // override and never touches mc.cameraEntity, so we must NOT call
        // setCameraEntity to "restore" it here — that would fire Vivecraft's
        // onCameraEntitySet re-origin and snap the headset view every frame.
        boolean vrMode = VivecraftCompat.isVrMode();
        Entity savedCameraEntity = mc.getCameraEntity();
        MinecraftClientAccessor accessor = (MinecraftClientAccessor) mc;
        net.minecraft.client.gl.Framebuffer mainFbo = accessor.cameramod$getFramebuffer();
        Perspective savedPerspective = mc.options.getPerspective();
        try {
            renderEditPreviews(mc, gameRenderer, tickCounter, now);
        } catch (Exception e) {
            Cameramod.LOGGER.error("CameraRenderer preview-only pass failed", e);
        } finally {
            EntityCullingCompat.endCameraWork();
            SodiumEntityCullingCompat.endCameraWork();
            accessor.cameramod$setFramebuffer(mainFbo);
            if (!vrMode) mc.setCameraEntity(savedCameraEntity);
            mc.options.setPerspective(savedPerspective);
            try {
                GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            } catch (Throwable ignored) {}
            rendering = false;
        }
    }

    private static void renderEditPreviews(MinecraftClient mc, GameRenderer gameRenderer,
                                           RenderTickCounter tickCounter, long now) {
        if (previewCameraUuid == null) return;
        long interval = previewBoostMode ? PREVIEW_BOOST_INTERVAL_NANOS : PREVIEW_INTERVAL_NANOS;
        // If the view was already captured from the main stream pass this frame,
        // we only need front/back — but still gate those by the interval.
        if (now - lastPreviewRenderNanos < interval) return;
        Entity previewCam = findCameraEntityByUuid(mc, previewCameraUuid);
        if (previewCam == null) return;
        lastPreviewRenderNanos = now;

        if (previewFbo == null
                || previewFbo.textureWidth != PREVIEW_W
                || previewFbo.textureHeight != PREVIEW_H) {
            if (previewFbo != null) {
                try { previewFbo.delete(); } catch (Throwable ignored) {}
            }
            previewFbo = new SimpleFramebuffer("cameramod_preview", PREVIEW_W, PREVIEW_H, true);
        }

        MinecraftClientAccessor accessor = (MinecraftClientAccessor) mc;
        accessor.cameramod$setFramebuffer(previewFbo);

        // In VR, point renderWorld at the preview camera via the getCameraEntity
        // override (NOT mc.setCameraEntity, which fires Vivecraft's
        // onCameraEntitySet head-tracking re-origin) and open a mono-render scope
        // so the preview renderWorld calls drop Vivecraft to its vanilla path and
        // can't hit the MultiPassTextureTarget without the VANILLA lever. This is
        // the same mechanism the working in-world VR camera pass uses. Flat
        // (non-VR) play keeps the plain field swap and no levers.
        boolean vrMode = VivecraftCompat.isVrMode();
        Entity savedOverride = cameraEntityOverride;
        if (vrMode) cameraEntityOverride = previewCam;
        else        mc.setCameraEntity(previewCam);

        UUID previewUuid = previewCameraUuid;
        // While we render this camera's previews, claim it as the "bound"
        // camera so the per-cam getters (getCameraFlipped, getCameraSeesChat,
        // getCameraNameTags, getCameraShowPlayerGuis) resolve to its overrides.
        // The previewed camera might not be the one currently streamed.
        UUID savedBoundUuid = boundCameraUuid;
        boundCameraUuid = previewUuid;
        boolean mono = vrMode && VivecraftCompat.beginMonoRender();
        try {
            if (!viewCapturedFromMainPass) {
                // FIRST_PERSON — what the camera sees. Also fans this frame out to
                // the tracked-camera thumbnail so the Cameras tab shows a live image
                // for cameras that were never activated (no main stream pass has run
                // for them, so without this their card stays blank).
                mc.options.setPerspective(Perspective.FIRST_PERSON);
                syncPrevPose(previewCam);
                gameRenderer.updateCamera(tickCounter);
                gameRenderer.renderWorld(tickCounter);
                int fbW = previewFbo.textureWidth;
                int fbH = previewFbo.textureHeight;
                if (getCameraSeesChat()) {
                    renderChatOverlay(mc, gameRenderer, fbW, fbH);
                }
                if (getCameraShowPlayerGuis() && mc.currentScreen != null
                        && !(mc.currentScreen instanceof dev.tggamesyt.cameramod.client.gui.CameraGuiScreen)
                        && !(mc.currentScreen instanceof dev.tggamesyt.cameramod.client.gui.EditCameraScreen)
                        && !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen
                             && getCameraSeesChat())) {
                    renderPlayerScreenOverlay(mc, gameRenderer, fbW, fbH);
                }
                boolean flipped = getCameraFlipped();
                capturePreviewFramebuffer(previewFbo, flipped, bytes -> {
                    previewFrameView = bytes;
                    if (previewUuid != null) {
                        CameramodClient.updateTrackedFrame(previewUuid, bytes, PREVIEW_W, PREVIEW_H);
                    }
                });
            }

            // THIRD_PERSON_FRONT/BACK — external views of the camera. No chat,
            // no player-screen overlay, no flip; these aren't "what the camera
            // sees", they're previews of where the camera is in the world.
            mc.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            syncPrevPose(previewCam);
            gameRenderer.updateCamera(tickCounter);
            gameRenderer.renderWorld(tickCounter);
            capturePreviewFramebuffer(previewFbo, bytes -> previewFrameFront = bytes);

            mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            syncPrevPose(previewCam);
            gameRenderer.updateCamera(tickCounter);
            gameRenderer.renderWorld(tickCounter);
            capturePreviewFramebuffer(previewFbo, bytes -> previewFrameBack = bytes);
        } finally {
            if (mono) VivecraftCompat.endMonoRender();
            boundCameraUuid = savedBoundUuid;
            if (vrMode) cameraEntityOverride = savedOverride;
        }

        // Bump version so the GUI re-uploads its textures. The async
        // copyTextureToBuffer callbacks fill the byte[] slightly after this
        // call returns, so the GUI may show a frame from the previous cycle
        // until the new bytes land — acceptable for a 5fps preview.
        previewFrameVersion++;
    }

    /**
     * Async read of the preview FBO into a fresh BGR top-down byte[],
     * handed to `sink` when the GPU copy completes.
     */
    private static void capturePreviewFramebuffer(Framebuffer framebuffer,
                                                  java.util.function.Consumer<byte[]> sink) {
        capturePreviewFramebuffer(framebuffer, false, sink);
    }

    private static void capturePreviewFramebuffer(Framebuffer framebuffer,
                                                  boolean flipped,
                                                  java.util.function.Consumer<byte[]> sink) {
        GpuTexture gpuTexture = framebuffer.getColorAttachment();
        if (gpuTexture == null) return;
        int texWidth  = gpuTexture.getWidth(0);
        int texHeight = gpuTexture.getHeight(0);
        int pixelSize = gpuTexture.getFormat().pixelSize();

        GpuBuffer gpuBuffer = RenderSystem.getDevice().createBuffer(
                () -> "CameraRenderer preview buffer", 9,
                texWidth * texHeight * pixelSize
        );
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
                gpuTexture, gpuBuffer, 0, () -> {
                    try (GpuBuffer.MappedView mappedView = encoder.mapBuffer(gpuBuffer, true, false)) {
                        ByteBuffer src = mappedView.data();
                        int srcStride = texWidth * pixelSize;
                        final int sw = PREVIEW_W;
                        final int sh = PREVIEW_H;
                        final int rowLen = sw * 3;
                        final int frameSize = rowLen * sh;
                        byte[] out = new byte[frameSize];
                        for (int y = 0; y < sh; y++) {
                            int srcY = y * texHeight / sh;
                            int srcRowOffset = srcY * srcStride;
                            int dstRow = (sh - 1 - y) * rowLen;
                            for (int x = 0; x < sw; x++) {
                                int srcX = x * texWidth / sw;
                                int si = srcRowOffset + srcX * pixelSize;
                                int outX = flipped ? (sw - 1 - x) : x;
                                int di = dstRow + outX * 3;
                                out[di]     = src.get(si + 2); // B
                                out[di + 1] = src.get(si + 1); // G
                                out[di + 2] = src.get(si);     // R
                            }
                        }
                        sink.accept(out);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        gpuBuffer.close();
                    }
                }, 0
        );
    }
}
