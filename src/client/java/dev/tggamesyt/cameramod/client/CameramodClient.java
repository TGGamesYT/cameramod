package dev.tggamesyt.cameramod.client;

import dev.tggamesyt.cameramod.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class CameramodClient implements ClientModInitializer {

    public static final Logger LOGGER = LogManager.getLogger(Cameramod.MOD_ID);
    public static final EntityModelLayer MODEL_CAMERA_LAYER = new EntityModelLayer(Identifier.of("camera"), "main");
    public static boolean isCamera = false;
    private static boolean originalF1State = false;
    private static boolean originalPauseState = true;

    // Camera mode: F9 keybind toggles the virtual camera-tool hotbar
    public static boolean cameraMode = false;
    private static KeyBinding cameraModeKey;

    // F9 tap-vs-hold detection. Tap = toggle camera mode (existing behavior).
    // Hold past threshold = open the camera GUI on the settings tab.
    private static boolean f9WasDown = false;
    private static boolean f9HoldFired = false;
    private static long f9PressStartNanos = 0L;
    private static final long F9_HOLD_THRESHOLD_NANOS = 300_000_000L;
    // Set by a screen when F9 closes it. While true, onClientTick ignores F9
    // edges until the key is physically released — otherwise the same press
    // that closed the screen would be detected as a fresh tap on the next
    // tick and immediately reopen the GUI.
    public static boolean f9LockedOutUntilRelease = false;

    // True when the current server has the cameramod plugin loaded
    public static boolean serverHasMod = false;

    // Client-only camera entities (no server mod required)
    // UUID → CameraEntity; managed here, not in the Minecraft world entity list
    public static final java.util.Map<java.util.UUID, CameraEntity> CLIENT_CAMERAS = new java.util.LinkedHashMap<>();

    // Cameras "ever activated in the current world or server" — used to populate
    // the Cameras tab in the camera GUI. Persisted per world/server next to
    // cameramod-cameras.dat. Removal happens implicitly: the GUI filters to
    // entries that still exist in mc.world or CLIENT_CAMERAS at display time.
    public static final class TrackedCamera {
        public final java.util.UUID uuid;
        public String name;
        public byte[] frame;        // last captured BGR frame, null until first bind
        public int frameVersion;    // bumped each time `frame` is replaced
        // Dimensions of `frame`.  0 = fall back to camwidth/camheight (legacy
        // streams) — kept so saved frames from previous sessions still load.
        public int frameW;
        public int frameH;
        // Per-camera setting overrides (null = use global default)
        public Boolean perCamFlipped;
        public Boolean perCamSeesChat;
        public Boolean perCamNameTags;
        public Boolean perCamShowPlayerGuis;
        public TrackedCamera(java.util.UUID uuid, String name) {
            this.uuid = uuid; this.name = name;
        }
    }
    public static final java.util.Map<java.util.UUID, TrackedCamera> TRACKED_CAMERAS = new java.util.LinkedHashMap<>();

    // Unique entity-id counter for client-only cameras. Negative IDs so they never
    // collide with server-assigned IDs (vanilla server uses positive IDs from 0 up).
    // Without this, addEntity() would use the global Entity.CURRENT_ID counter, and
    // the server's next entity (e.g. an armor stand) at the same numeric ID would
    // overwrite our CameraEntity in the client's entity table, killing the camera
    // and eventually kicking the player with a data-tracker type mismatch.
    //
    // Base is -1.8 billion, NOT -1: server-side plugins that send packet-only fake
    // entities (holograms, NPC nametags, cosmetics) commonly allocate fake IDs from
    // their own counter decrementing from -1. After an hour or two on such a server
    // that counter reaches -1/-2/-3... and ClientWorld.addEntity() silently DISCARDS
    // whatever entity already holds the ID — our camera. The plugin then refreshes
    // its fake entity periodically, re-discarding the camera in a loop (invisible),
    // while its move/rotation packets land on the camera whenever it briefly holds
    // the ID (stream rotation thrashing). Parking our IDs ~1.8B away makes that
    // collision unreachable in practice.
    private static final java.util.concurrent.atomic.AtomicInteger clientCameraIdCounter
        = new java.util.concurrent.atomic.AtomicInteger(-1_800_000_000);
    private static int nextClientCameraId() {
        return clientCameraIdCounter.getAndDecrement();
    }

    // Per-frame attachment/fixer pipeline, formerly registered on Fabric's
    // WorldRenderEvents.START (removed in the 1.21.9 world-render refactor).
    // WorldRendererMixin now invokes onWorldRenderStart() at the head of
    // WorldRenderer.render — the same point the old event fired from.
    private static Runnable worldRenderStartHandler;
    public static void onWorldRenderStart() {
        if (worldRenderStartHandler != null) worldRenderStartHandler.run();
    }

    // Persistent cache of CameraEntity references for any camera we've ever seen.
    // Optimization mods (Sodium, Embeddium, Iris…) can prune entities from
    // mc.world.getEntities() iteration when their section is outside the player's
    // current visibility set. Without this cache, allCameras in WorldRenderEvents.START
    // would miss server-mode cameras whose section is temporarily outside the player's
    // view, causing the fixer/attachment to stop running for that entity until the
    // player looks back at it. Client-only cameras are already protected by CLIENT_CAMERAS;
    // this cache covers server cameras. Stale (discarded) entries are pruned each frame.
    static final java.util.Map<java.util.UUID, dev.tggamesyt.cameramod.CameraEntity>
            knownCameraEntities = new java.util.HashMap<>();

    // Client-mode fixer/attacher two-step selection state
    private static java.util.UUID clientSelectedCamera = null;

    // Most-recent camera that was (or currently is) attached to the local player.
    // Updated lazily from the tick loop whenever a camera's attach target is the
    // player. Persists across detach so the GUI sidebar's "toggle attaching"
    // button knows which camera to re-attach when toggled back on.
    public static java.util.UUID lastAttachedToPlayerCameraUuid = null;

    // Local player's UUID, cached while connected. On a clean disconnect
    // client.player is frequently already null by the time DISCONNECT fires, so
    // the attached-camera capture there can't read the player's UUID directly —
    // it falls back to this. Without it, cameras attached to the player were
    // never snapshotted on disconnect and vanished on reconnect.
    private static java.util.UUID localPlayerUuid = null;

    // Deferred edit-screen open for a just-spawned camera. On server-mod the
    // CameraEntity doesn't exist client-side until the server's spawn packet
    // arrives, so opening EditCameraScreen immediately would show empty
    // position/rotation fields. END_CLIENT_TICK opens the screen as soon as the
    // entity is found (or after a short timeout as a fallback).
    private static java.util.UUID pendingEditScreenCamUuid = null;
    private static int            pendingEditScreenTimeout  = 0;

    // Client-mode mover state
    private static java.util.UUID clientMoverCamUuid = null;
    private static double clientMoverDistance = 5.0;
    public static boolean clientMoverActive = false;
    // Client-mode zoomer state
    private static java.util.UUID clientZoomerCamUuid = null;

    // ─── External edit modes driven by EditCameraScreen ──────────────────────
    // While these are set, the EditCameraScreen has been closed temporarily so
    // the player can interact with the world. A click in MouseMixin exits the
    // mode and reopens EditCameraScreen for the same camera.
    public static java.util.UUID editMoveCamUuid       = null;
    public static double         editMoveDistance      = 5.0;
    public static boolean        editMoveServerStarted = false;
    public static java.util.UUID editSelectCamUuid     = null;
    /** 1 = fixer-target select, 2 = attach-target select. */
    public static int            editSelectKind        = 0;

    // Virtual hotbar items shown when camera mode is active (slots 0-8)
    public static final ItemStack[] CAMERA_HOTBAR_STACKS = new ItemStack[] {
        new ItemStack(ServerItems.CAMERA_ITEM),
        new ItemStack(ServerItems.CAMERA_ACTIVATOR),
        new ItemStack(ServerItems.CAMERA_ORIENTER),
        new ItemStack(ServerItems.CAMERA_MOVER),
        new ItemStack(ServerItems.CAMERA_FIXER),
        new ItemStack(ServerItems.CAMERA_ZOOMER),
        new ItemStack(ServerItems.CAMERA_GRAVITY),
        new ItemStack(ServerItems.CAMERA_ATTACHER),
        new ItemStack(ServerItems.CAMERA_REMOVER),
    };

    // Attached-camera restore: persisted to disk, populated on DISCONNECT, applied on next JOIN.
    private static java.util.List<PendingCamera> pendingCameraRestore = new java.util.ArrayList<>();
    // Tick countdown until cameras are recreated after JOIN (-1 = idle, 0 = fire now).
    private static int restoreTickDelay = -1;

    // Resolved camera save file for the current session.
    // Singleplayer: {worldSaveDir}/cameramod-cameras.dat (travels with the world).
    // Multiplayer: config/cameramod-cameras/mp_{address}.dat.
    private static Path currentSavePath = null;

    private record PendingCamera(
        double x, double y, double z,
        float yaw, float pitch,
        boolean wasBound,
        boolean streaming,
        byte attachMode,
        boolean gravityEnabled,
        java.util.UUID fixedTargetUuid,
        byte fixerMode,
        // Preserve the camera's identity across save/restore so it keeps its
        // TRACKED_CAMERAS entry (name + per-cam settings) and 3D-model custom
        // name. Without this, restore minted a fresh random UUID each time —
        // the camera "lost its name" on proxy sub-server hops and looked brand
        // new on every reconnect. May be null for files saved before this field
        // existed (restore then falls back to a fresh UUID).
        java.util.UUID uuid,
        String name
    ) {}

    // Client-side state flags (set by S2C packets)
    public static boolean moverActive = false;
    public static boolean zoomerActive = false;

    // Smoothed target height for fixer "Look At" mode (lerps during sneak transitions)
    private static final java.util.HashMap<java.util.UUID, Float> smoothedTargetHeight = new java.util.HashMap<>();
    // Last 4 raw yaw samples per camera, for median-of-5 spike rejection
    // (median-of-N rejects up to floor((N-1)/2) consecutive outliers, so 5 → 2-tick)
    private static final java.util.HashMap<java.util.UUID, float[]> rawYawHistory = new java.util.HashMap<>();
    // Filtered entity yaw shared from attachment pass to fixer pass (per cam)
    private static final java.util.HashMap<java.util.UUID, Float> filteredAttachYaw = new java.util.HashMap<>();

    // Previous frame's fixed-target UUID per camera, to detect when the fixer is removed.
    // On the frame where it transitions non-null → null we send the current yaw/pitch to
    // the server so it persists across unload/reload.
    private static final java.util.HashMap<java.util.UUID, java.util.UUID> lastFixedTarget = new java.util.HashMap<>();

    // Cached relative cam rotation (cam yaw/pitch in target's local frame), captured
    // ONCE when fixer+head-attach become active for a given offset. Reused every
    // frame so cam rotation is just `targetYaw + cachedRelYaw` — no per-frame
    // look-at recompute, no floating drift, no jitter.
    private static final class CachedRel {
        final float relYaw, pitch;
        final double offX, offY, offZ;
        CachedRel(float relYaw, float pitch, double ox, double oy, double oz) {
            this.relYaw = relYaw; this.pitch = pitch; this.offX = ox; this.offY = oy; this.offZ = oz;
        }
    }
    private static final java.util.HashMap<java.util.UUID, CachedRel> cachedRelRotation = new java.util.HashMap<>();

    // Cached yaw offset between the camera and its head-attach target, captured
    // ONCE per camera and reused every frame: cam.yaw = filteredTargetYaw + relYaw.
    // Replaces the old delta-yaw accumulator (lastAttachEntityYaw + dYaw), which
    // could drift if the server re-broadcast a stale cam yaw between frames.
    // Recaptured when the user externally rotates the camera (Orienter, edit
    // screen Rotate mode) — we detect that by comparing cam.yaw against the
    // value we last wrote.
    private static final java.util.HashMap<java.util.UUID, Float> cachedAttachRelYaw = new java.util.HashMap<>();
    private static final java.util.HashMap<java.util.UUID, Float> lastWrittenAttachYaw = new java.util.HashMap<>();

    @Override
    public void onInitializeClient() {
        // Camera output dimensions: detect the primary monitor's native resolution
        // and create the SoftCam virtual camera from CLIENT_STARTED. Doing this
        // here (in onInitializeClient) is too early — Fabric invokes client
        // entrypoints inside MinecraftClient.<init> BEFORE RenderSystem.initBackendSystem
        // has called glfwInit, so any GLFW call poisons the GLFW error queue and
        // the game crashes a moment later when MC's _initGlfw tries to start.
        // CLIENT_STARTED fires after MC is fully initialised so GLFW is up.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try {
                long monitor = GLFW.glfwGetPrimaryMonitor();
                org.lwjgl.glfw.GLFWVidMode mode = monitor != 0 ? GLFW.glfwGetVideoMode(monitor) : null;
                if (mode != null
                        && mode.width()  >= 480 && mode.width()  <= 15360
                        && mode.height() >= 270 && mode.height() <= 8640) {
                    Cameramod.camwidth  = mode.width();
                    Cameramod.camheight = mode.height();
                } else {
                    Cameramod.camwidth  = 1920;
                    Cameramod.camheight = 1080;
                }
            } catch (Throwable t) {
                Cameramod.camwidth  = 1920;
                Cameramod.camheight = 1080;
            }

            SoftCam.initialize();
            Cameramod.softcamCamera = SoftCam.createCamera(Cameramod.camwidth, Cameramod.camheight, Cameramod.camframerate);
        });

        // Drop the cached off-image whenever client resources reload (pack
        // toggled, F3+T, /reload of client assets) so a new off animation/image
        // is picked up without restarting the game.
        net.fabricmc.fabric.api.resource.ResourceManagerHelper
                .get(net.minecraft.resource.ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.of(Cameramod.MOD_ID, "off_image_reload");
                    }
                    @Override
                    public void reload(net.minecraft.resource.ResourceManager manager) {
                        CameraRenderer.invalidateOffImageCache();
                    }
                });

        // Start the MJPEG stream server (localhost:7236)
        CameraStreamServer.start();

        // Camera mode keybind (F9)
        cameraModeKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.cameramod.camera_mode", GLFW.GLFW_KEY_F9, KeyBinding.Category.GAMEPLAY)
        );

        loadClientConfig();

        // Detect whether the server has the mod on join/leave
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            serverHasMod = ClientPlayNetworking.canSend(CameraServerThing.CameraScrollC2SPayload.ID);

            Path newPath = resolveSavePath(client);

            // Proxy backend transfers don't always fire DISCONNECT, so CLIENT_CAMERAS
            // can still hold entities tied to the OLD ClientWorld. Their world ref is
            // stale — the 3D model won't render even though streaming still works off
            // their cached pos/yaw. Snapshot them into pendingCameraRestore so the
            // countdown recreates fresh CameraEntity instances in the new world.
            boolean fromLiveSnapshot = !CLIENT_CAMERAS.isEmpty() && client.player != null;
            if (fromLiveSnapshot) {
                java.util.UUID playerUuid = client.player.getUuid();
                java.util.UUID currentBound = CameraRenderer.getBoundCameraUuid();
                boolean currentStreaming = CameraRenderer.isStreamingEnabled();
                for (CameraEntity cam : CLIENT_CAMERAS.values()) {
                    if (playerUuid.equals(cam.getAttachTargetUuid())) {
                        net.minecraft.util.math.Vec3d offset = cam.getAttachOffset();
                        boolean wasThisBound = cam.getUuid().equals(currentBound);
                        pendingCameraRestore.add(new PendingCamera(
                            offset.x, offset.y, offset.z,
                            cam.getYaw(), cam.getPitch(),
                            wasThisBound,
                            currentStreaming && wasThisBound,
                            cam.getAttachMode(),
                            cam.isGravityEnabled(),
                            cam.getFixedTargetUuid(),
                            cam.getFixerMode(),
                            cam.getUuid(),
                            bestKnownName(cam)
                        ));
                    }
                }
                CLIENT_CAMERAS.clear();
                knownCameraEntities.clear();
            }

            // If the save target changed (different world/server), cameras from the
            // previous session must not bleed into this one. Discard them and load
            // whatever lives at the new path.
            boolean pathChanged = newPath != null && !newPath.equals(currentSavePath);
            if (pathChanged && !pendingCameraRestore.isEmpty()) {
                pendingCameraRestore.clear();
                fromLiveSnapshot = false;
            }

            currentSavePath = newPath;

            // No cameras pending — load from disk for this world/server.
            if (pendingCameraRestore.isEmpty() && newPath != null) {
                loadAttachedCameras(newPath);
                fromLiveSnapshot = false;
            }

            // The tracked-cameras list is independent of the attached-cameras
            // restore and applies to every join — fresh load on path change so
            // entries from another world/server don't bleed in.
            if (pathChanged) TRACKED_CAMERAS.clear();
            if (newPath != null) loadTrackedCameras(newPath);

            if (!pendingCameraRestore.isEmpty()) {
                // Proxy reconnect (same path, cameras snapshotted from live world):
                // restore on the first tick the player is available — no positional
                // settling needed, the server already has them in place.
                // Fresh join (disk load): wait 3 s for the server to position the
                // player at their real spawn before we attach cameras.
                restoreTickDelay = fromLiveSnapshot ? 1 : 60;
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            java.util.List<PendingCamera> nextPending = new java.util.ArrayList<>();
            // client.player is usually already null here on a clean disconnect, so
            // fall back to the UUID we cached while connected — otherwise none of
            // the cameras attached to the player get captured and they're lost on
            // reconnect.
            java.util.UUID playerUuid = client.player != null ? client.player.getUuid() : localPlayerUuid;
            if (playerUuid != null) {
                java.util.UUID currentBound = CameraRenderer.getBoundCameraUuid();
                boolean currentStreaming = CameraRenderer.isStreamingEnabled();
                for (CameraEntity cam : CLIENT_CAMERAS.values()) {
                    if (playerUuid.equals(cam.getAttachTargetUuid())) {
                        // Use the stored attachOffset directly — it's already in the
                        // correct coordinate space for the mode (world-space for Fixed,
                        // local-space for Orbit). Recalculating from world positions
                        // would break Orbit mode on restore.
                        net.minecraft.util.math.Vec3d offset = cam.getAttachOffset();
                        boolean wasThisBound = cam.getUuid().equals(currentBound);
                        nextPending.add(new PendingCamera(
                            offset.x, offset.y, offset.z,
                            cam.getYaw(), cam.getPitch(),
                            wasThisBound,
                            currentStreaming && wasThisBound,
                            cam.getAttachMode(),
                            cam.isGravityEnabled(),
                            cam.getFixedTargetUuid(),
                            cam.getFixerMode(),
                            cam.getUuid(),
                            bestKnownName(cam)
                        ));
                    }
                }
            }
            // If no cameras are active but a restore is still pending (rapid hop before
            // the countdown fired), carry the pending list forward unchanged.
            if (nextPending.isEmpty() && !pendingCameraRestore.isEmpty()) {
                nextPending.addAll(pendingCameraRestore);
            }
            pendingCameraRestore.clear();
            pendingCameraRestore.addAll(nextPending);
            if (currentSavePath != null) {
                saveAttachedCameras(currentSavePath);
                saveTrackedCameras(currentSavePath);
            }
            // If the player disconnects while in /setcamera mode, restore the
            // pause-on-lost-focus option. Otherwise the SetCameraS2C "exit
            // camera" packet never arrives, the option stays at false, and MC
            // persists that value to options.txt on quit so future sessions
            // stop showing the escape screen when the window loses focus.
            if (isCamera) {
                client.options.pauseOnLostFocus = originalPauseState;
                client.options.hudHidden = originalF1State;
                if (client.player != null) client.setCameraEntity(client.player);
                isCamera = false;
            }
            serverHasMod = false;
            cameraMode = false;
            CLIENT_CAMERAS.clear();
            TRACKED_CAMERAS.clear();
            knownCameraEntities.clear();
            restoreTickDelay = -1;
            clientSelectedCamera = null;
            clientMoverActive = false;
            clientMoverCamUuid = null;
            clientZoomerCamUuid = null;
        });

        // Ensure SoftCam camera is cleaned up on game exit so it can be recreated next launch
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (Cameramod.softcamCamera != null) {
                SoftCam.deleteCamera(Cameramod.softcamCamera);
                Cameramod.softcamCamera = null;
            }
        }));

        EntityRendererRegistry.register(Cameramod.CAMERA_ENTITY_ENTITY_TYPE, CameraEntityRenderer::new);
        EntityModelLayerRegistry.registerModelLayer(MODEL_CAMERA_LAYER, CameraEntityModel::getTexturedModelData);

        // Right-click interception is handled by ClientInteractionMixin
        // (mixes into MinecraftClient.doItemUse to fully cancel vanilla use).

        // Client-side gamerule overrides: /cm flip|seeschat|nametags <true|false>
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                ClientCommandManager.literal("cm")
                    .then(ClientCommandManager.literal("flip")
                        .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(ctx -> {
                                boolean val = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value");
                                CameraRenderer.setLocalFlipped(val);
                                saveClientConfig();
                                ctx.getSource().sendFeedback(net.minecraft.text.Text.literal("cameraFlipped set to " + val + " (local override)"));
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("seeschat")
                        .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(ctx -> {
                                boolean val = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value");
                                CameraRenderer.setLocalSeesChat(val);
                                saveClientConfig();
                                ctx.getSource().sendFeedback(net.minecraft.text.Text.literal("cameraSeesChat set to " + val + " (local override)"));
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("nametags")
                        .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(ctx -> {
                                boolean val = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value");
                                CameraRenderer.setLocalNameTags(val);
                                saveClientConfig();
                                ctx.getSource().sendFeedback(net.minecraft.text.Text.literal("cameraNameTags set to " + val + " (local override)"));
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("cameragui")
                        .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(ctx -> {
                                boolean val = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value");
                                CameraRenderer.setLocalGuiMode(val);
                                saveClientConfig();
                                ctx.getSource().sendFeedback(net.minecraft.text.Text.literal("cameraGuiMode set to " + val + " (local override)"));
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("playerguis")
                        .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(ctx -> {
                                boolean val = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value");
                                CameraRenderer.setLocalShowPlayerGuis(val);
                                saveClientConfig();
                                ctx.getSource().sendFeedback(net.minecraft.text.Text.literal("cameraShowPlayerGuis set to " + val + " (local override)"));
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("streamfps")
                        .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 240))
                            .executes(ctx -> {
                                int val = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value");
                                CameraRenderer.setLocalStreamFps(val);
                                saveClientConfig();
                                ctx.getSource().sendFeedback(net.minecraft.text.Text.literal("cameraStreamFps set to " + val + " (local override)"));
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("virtualfps")
                        .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 240))
                            .executes(ctx -> {
                                int val = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value");
                                CameraRenderer.setLocalVirtualFps(val);
                                saveClientConfig();
                                ctx.getSource().sendFeedback(net.minecraft.text.Text.literal("cameraVirtualFps set to " + val + " (local override)"));
                                return 1;
                            })))
            );
        });

        // Drop per-camera client state when the entity unloads (player death,
        // moving out of tracking range, etc.). The attacher's delta-yaw path
        // would otherwise apply the entire rotation the player accumulated
        // while the camera was gone, snapping the cam to a wrong heading on
        // reload. Re-logging didn't reproduce because the world tick is
        // stopped at the title screen, so no fresh delta is computed.
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof CameraEntity cam) {
                java.util.UUID id = entity.getUuid();
                // Keep client-only cameras in CLIENT_CAMERAS even after the world
                // entity manager removes them (chunk unloads, etc.). The attachment
                // logic and renderer both fall back to CLIENT_CAMERAS, so streaming
                // and position tracking keep working without the entity in the world.
                // Server-side cameras are removed normally so stale UUIDs don't linger.
                if (!cam.isClientOnly()) {
                    CLIENT_CAMERAS.remove(id);
                }
                smoothedTargetHeight.remove(id);
                rawYawHistory.remove(id);
                filteredAttachYaw.remove(id);
                cachedRelRotation.remove(id);
                cachedAttachRelYaw.remove(id);
                lastWrittenAttachYaw.remove(id);
                lastFixedTarget.remove(id);
            }
        });

        // Manual camera view switching via /setcamera
        ClientPlayNetworking.registerGlobalReceiver(CameraServerThing.SetCameraS2CPayload.ID, (payload, context) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            Mouse mouse = mc.mouse;
            if (isCamera) {
                isCamera = false;
                mc.setCameraEntity(context.player());
                mc.gameRenderer.getCamera().reset();
                mc.options.hudHidden = originalF1State;
                mc.options.pauseOnLostFocus = originalPauseState;
            } else {
                isCamera = true;
                mc.setCameraEntity(context.player().getEntityWorld().getEntity(payload.uuid()));
                mc.gameRenderer.getCamera().reset();
                originalF1State = mc.options.hudHidden;
                originalPauseState = mc.options.pauseOnLostFocus;
                mc.options.pauseOnLostFocus = false;
                mc.options.hudHidden = true;
                mouse.unlockCursor();
            }
        });

        // Camera bind/unbind for Virtual Camera rendering
        ClientPlayNetworking.registerGlobalReceiver(CameraServerThing.BindCameraS2CPayload.ID, (payload, context) -> {
            CameraRenderer.setBoundCamera(payload.cameraUuid());
            addTrackedCamera(payload.cameraUuid());
        });

        ClientPlayNetworking.registerGlobalReceiver(CameraServerThing.UnbindCameraS2CPayload.ID, (payload, context) -> {
            CameraRenderer.clearBoundCamera();
        });

        // Mover/Zoomer/Streaming state updates
        ClientPlayNetworking.registerGlobalReceiver(CameraServerThing.CameraItemStateS2CPayload.ID, (payload, context) -> {
            if (payload.type() == 0) {
                moverActive = payload.active();
            } else if (payload.type() == 1) {
                zoomerActive = payload.active();
            } else if (payload.type() == 2) {
                CameraRenderer.setStreamingEnabled(payload.active());
            } else if (payload.type() == 3) {
                CameraRenderer.setCameraSeesChat(payload.active());
            } else if (payload.type() == 4) {
                CameraRenderer.setCameraFlipped(payload.active());
            } else if (payload.type() == 5) {
                CameraRenderer.setCameraNameTags(payload.active());
            } else if (payload.type() == 6) {
                CameraRenderer.setCameraGuiModeSynced(payload.active());
            } else if (payload.type() == 7) {
                CameraRenderer.setCameraShowPlayerGuisSynced(payload.active());
            }
        });

        // Int-valued settings (FPS caps): type 0 = stream, type 1 = virtual cam.
        ClientPlayNetworking.registerGlobalReceiver(CameraServerThing.CameraIntSettingS2CPayload.ID, (payload, context) -> {
            if (payload.type() == 0)      CameraRenderer.setStreamFpsSynced(payload.value());
            else if (payload.type() == 1) CameraRenderer.setVirtualFpsSynced(payload.value());
        });

        // Per-frame client-side updates for all camera entities:
        // - Fixer rotation (smooth interpolated tracking)
        // - Attachment position (smooth lerp avoids 20tps server lag)
        worldRenderStartHandler = () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null) return;
            // Our own camera pass calls gameRenderer.renderWorld() which makes
            // Fabric re-fire WorldRenderEvents.START. Re-running the
            // attachment/fixer pipeline a second time within one frame creates
            // a 2-state oscillation on "Look At" tracking because the target's
            // lastRender values shift between the two invocations. Only run
            // during the player's render pass.
            if (CameraRenderer.isRendering()) return;
            float tickDelta = mc.getRenderTickCounter().getTickProgress(false);
            // Pass 1: attachment position. Pass 2: fixer rotation.
            // Order matters — fixer reads cam position to compute look-at angle, so
            // the cam must already be at its final frame position when fixer runs,
            // otherwise the angle is one frame stale and visibly jitters.
            // Build combined list: world entities first, then client-only cameras,
            // then server cameras we've seen before but that may be temporarily
            // excluded from mc.world.getEntities() by optimization mods.
            java.util.List<CameraEntity> allCameras = new java.util.ArrayList<>();
            java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof CameraEntity cam && seen.add(cam.getUuid())) {
                    allCameras.add(cam);
                    knownCameraEntities.put(cam.getUuid(), cam); // keep reference
                }
            }
            for (CameraEntity cam : CLIENT_CAMERAS.values()) {
                if (seen.add(cam.getUuid())) allCameras.add(cam);
            }
            // Purge discarded entries, then add any cameras not yet seen this frame.
            knownCameraEntities.values().removeIf(Entity::isRemoved);
            for (CameraEntity cam : knownCameraEntities.values()) {
                if (seen.add(cam.getUuid())) allCameras.add(cam);
            }

            for (CameraEntity cam : allCameras) {
                java.util.UUID attachUuid = cam.getAttachTargetUuid();
                if (attachUuid == null) {
                    // Detached: drop this camera's orbit-mode rotation caches so a
                    // LATER re-attach in Head (Orbit) mode re-captures the relative
                    // yaw from the camera's CURRENT facing. Without this the stale
                    // relYaw from the previous attach session survives (the World-mode
                    // and unload cleanups don't run on a plain detach — this loop
                    // continues past them), and the first re-attach frame snaps the
                    // camera to playerYaw + oldRelYaw instead of keeping its facing.
                    java.util.UUID detachedId = cam.getUuid();
                    cachedAttachRelYaw.remove(detachedId);
                    lastWrittenAttachYaw.remove(detachedId);
                    rawYawHistory.remove(detachedId);
                    filteredAttachYaw.remove(detachedId);
                    continue;
                }
                Entity attachTarget = null;
                // Local player isn't always returned by world.getEntities() iteration on the
                // client — check it explicitly so client-side attached cameras follow the
                // player on a freshly-joined server (e.g. proxy transfer).
                if (mc.player != null && mc.player.getUuid().equals(attachUuid)) {
                    attachTarget = mc.player;
                } else {
                    for (Entity e : mc.world.getEntities()) {
                        if (e.getUuid().equals(attachUuid)) { attachTarget = e; break; }
                    }
                }
                if (attachTarget == null) continue;
                net.minecraft.util.math.Vec3d offset = cam.getAttachOffset();
                double ax = net.minecraft.util.math.MathHelper.lerp(tickDelta, attachTarget.lastRenderX, attachTarget.getX());
                double ay = net.minecraft.util.math.MathHelper.lerp(tickDelta, attachTarget.lastRenderY, attachTarget.getY());
                double az = net.minecraft.util.math.MathHelper.lerp(tickDelta, attachTarget.lastRenderZ, attachTarget.getZ());
                double tx, tz;
                if (cam.getAttachMode() == 1) {
                    java.util.UUID camId = cam.getUuid();
                    // Single source of truth for entity yaw this frame. Used for BOTH
                    // offset rotation (this pass) and fixer look-at (next pass).
                    // Local player → input-driven yaw, else network-lerped yaw.
                    float rawYaw = (attachTarget == mc.player)
                            ? net.minecraft.util.math.MathHelper.lerp(tickDelta, mc.player.lastYaw, mc.player.getYaw())
                            : net.minecraft.util.math.MathHelper.lerp(tickDelta, attachTarget.lastYaw, attachTarget.getYaw());

                    // Median-of-5 spike filter: rejects up to 2 consecutive outlier
                    // ticks. Wrap-aware via deltas relative to the most recent stable.
                    float[] hist = rawYawHistory.getOrDefault(camId, new float[]{rawYaw, rawYaw, rawYaw, rawYaw});
                    float ref = hist[3];
                    float[] deltas = new float[]{
                            net.minecraft.util.math.MathHelper.wrapDegrees(hist[0] - ref),
                            net.minecraft.util.math.MathHelper.wrapDegrees(hist[1] - ref),
                            net.minecraft.util.math.MathHelper.wrapDegrees(hist[2] - ref),
                            0f,
                            net.minecraft.util.math.MathHelper.wrapDegrees(rawYaw - ref),
                    };
                    float[] sorted = deltas.clone();
                    java.util.Arrays.sort(sorted);
                    float filteredYaw = ref + sorted[2];
                    hist[0] = hist[1]; hist[1] = hist[2]; hist[2] = hist[3]; hist[3] = rawYaw;
                    rawYawHistory.put(camId, hist);
                    filteredAttachYaw.put(camId, filteredYaw);

                    // Position offset orbits with entity yaw (filtered)
                    float yawRad = (float) (filteredYaw * Math.PI / 180.0);
                    tx = ax + offset.x * Math.cos(yawRad) - offset.z * Math.sin(yawRad);
                    tz = az + offset.x * Math.sin(yawRad) + offset.z * Math.cos(yawRad);


                    // Cam facing: cached relative-yaw between cam and target,
                    // re-captured if the user externally changes cam yaw (Orienter,
                    // EditCameraScreen Rotate). cam.yaw = target.yaw + relYaw is
                    // drift-free — no accumulation, server reasserts get overridden
                    // each frame instead of becoming the new base.
                    if (cam.getFixedTargetUuid() == null) {
                        Float relYaw = cachedAttachRelYaw.get(camId);
                        Float lastWritten = lastWrittenAttachYaw.get(camId);
                        if (relYaw == null
                                || (lastWritten != null
                                    && Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(cam.getYaw() - lastWritten)) > 1.0f)) {
                            relYaw = net.minecraft.util.math.MathHelper.wrapDegrees(cam.getYaw() - filteredYaw);
                            cachedAttachRelYaw.put(camId, relYaw);
                        }
                        float newYaw = net.minecraft.util.math.MathHelper.wrapDegrees(filteredYaw + relYaw);
                        cam.setYaw(newYaw);
                        cam.setHeadYaw(newYaw);
                        cam.setBodyYaw(newYaw);
                        cam.lastYaw = newYaw;
                        lastWrittenAttachYaw.put(camId, newYaw);
                    } else {
                        // Fixer is driving the cam yaw — clear the cache so when
                        // the fixer is removed we re-capture fresh.
                        cachedAttachRelYaw.remove(camId);
                        lastWrittenAttachYaw.remove(camId);
                    }
                } else {
                    tx = ax + offset.x;
                    tz = az + offset.z;
                    // World/Fixed mode: drop the head-mode yaw caches AND the
                    // median-filter history so the next switch back to head
                    // re-captures the relative angle from the player's CURRENT
                    // facing. If we only cleared the cache, the first head-mode
                    // frame would feed the median-of-5 filter a single fresh
                    // sample mixed with 4 stale ones from the previous head
                    // session — the median would pick a stale value, relYaw
                    // would be captured against it, and on subsequent frames
                    // (as the filter converges to current) the cam would drift
                    // away by (currentYaw - staleFilteredYaw). That's the
                    // "cam loses its rotation" symptom on World→Head switch.
                    java.util.UUID camId = cam.getUuid();
                    cachedAttachRelYaw.remove(camId);
                    lastWrittenAttachYaw.remove(camId);
                    rawYawHistory.remove(camId);
                    filteredAttachYaw.remove(camId);
                }
                double ty = ay + offset.y;
                cam.setPosition(tx, ty, tz);
                cam.lastRenderX = tx;
                cam.lastRenderY = ty;
                cam.lastRenderZ = tz;
                cam.lastX = tx;
                cam.lastY = ty;
                cam.lastZ = tz;
            }

            for (CameraEntity cam : allCameras) {

                // --- Fixer rotation ---
                java.util.UUID camId = cam.getUuid();
                java.util.UUID targetUuid = cam.getFixedTargetUuid();

                // Detect fixer removal: when fixed target goes non-null → null,
                // send the current client-side yaw/pitch to the server so it
                // persists across unload/reload (the fixer never synced to server).
                java.util.UUID prevTarget = lastFixedTarget.get(camId);
                lastFixedTarget.put(camId, targetUuid);
                if (prevTarget != null && targetUuid == null) {
                    ClientPlayNetworking.send(new CameraServerThing.CameraOrientC2SPayload(
                            camId, cam.getYaw(), cam.getPitch()));
                }

                if (targetUuid != null) {
                    Entity target = null;
                    if (mc.player != null && mc.player.getUuid().equals(targetUuid)) {
                        target = mc.player;
                    } else {
                        for (Entity e : mc.world.getEntities()) {
                            if (e.getUuid().equals(targetUuid)) { target = e; break; }
                        }
                    }
                    if (target == null) target = CLIENT_CAMERAS.get(targetUuid);
                    if (target != null) {
                        float yaw, pitch;
                        if (cam.getFixerMode() == 1) {
                            // Look Same Way mode: copy target's interpolated rotation
                            yaw = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastYaw, target.getYaw());
                            pitch = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastPitch, target.getPitch());
                        } else {
                            // Look At mode: face toward target
                            // Smooth the height offset to avoid jumps during sneak transitions
                            float desiredHeight = target.isSneaking() ? target.getHeight() * 0.625f : target.getHeight() * 0.725f;
                            float currentSmoothed = smoothedTargetHeight.getOrDefault(camId, desiredHeight);
                            float lerpSpeed = 0.15f;
                            currentSmoothed += (desiredHeight - currentSmoothed) * lerpSpeed;
                            smoothedTargetHeight.put(camId, currentSmoothed);

                            // Fast path: cam fixed-to + head-attached-to SAME target.
                            // Use cached relative rotation. For player target use
                            // CLIENT player's own rotation (input-driven, jitter-free,
                            // not the network-lerped copy that micro-jitters).
                            java.util.UUID attachId = cam.getAttachTargetUuid();
                            if (attachId != null && attachId.equals(targetUuid) && cam.getAttachMode() == 1) {
                                net.minecraft.util.math.Vec3d off = cam.getAttachOffset();
                                CachedRel rel = cachedRelRotation.get(camId);
                                if (rel == null
                                        || rel.offX != off.x || rel.offY != off.y || rel.offZ != off.z) {
                                    // (Re)capture once: relative look-at angle in target-local frame
                                    double localYaw = Math.atan2(-off.z, -off.x) * (180.0 / Math.PI) - 90.0;
                                    double horiz = Math.sqrt(off.x * off.x + off.z * off.z);
                                    double dyRel = currentSmoothed - off.y - cam.getStandingEyeHeight();
                                    float pitchCap = (float) (-(Math.atan2(dyRel, horiz) * (180.0 / Math.PI)));
                                    rel = new CachedRel((float) localYaw, pitchCap, off.x, off.y, off.z);
                                    cachedRelRotation.put(camId, rel);
                                }
                                // Read SAME yaw the attachment pass used for offset rotation
                                Float sharedYaw = filteredAttachYaw.get(camId);
                                float entYaw = (sharedYaw != null) ? sharedYaw
                                        : net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastYaw, target.getYaw());
                                yaw = entYaw + rel.relYaw;
                                pitch = rel.pitch;
                            } else {
                                cachedRelRotation.remove(camId);
                                double tx = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastRenderX, target.getX());
                                double ty = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastRenderY, target.getY())
                                        + currentSmoothed;
                                double tz = net.minecraft.util.math.MathHelper.lerp(tickDelta, target.lastRenderZ, target.getZ());
                                // The client owns this camera's position (set every
                                // frame in this handler, or held static by the server).
                                // Use its actual current position — never a lerp of
                                // lastRenderX→getX(). When the camera is off-screen,
                                // optimization mods cull it from rendering so the
                                // entity renderer never refreshes lastRenderX, while
                                // the server-sync interpolator nudges getX(); the lerp
                                // between the two then swings every frame, making the
                                // look-at angle oscillate ("moves toward, snaps back").
                                double cx = cam.getX();
                                double cy = cam.getY() + cam.getStandingEyeHeight();
                                double cz = cam.getZ();

                                double dx = tx - cx;
                                double dy = ty - cy;
                                double dz = tz - cz;
                                double horizontal = Math.sqrt(dx * dx + dz * dz);

                                yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
                                pitch = (float) (-(Math.atan2(dy, horizontal) * (180.0 / Math.PI)));
                            }
                        }

                        cam.setYaw(yaw);
                        cam.setPitch(pitch);
                        cam.setHeadYaw(yaw);
                        cam.setBodyYaw(yaw);
                        cam.lastYaw = yaw;
                        cam.lastPitch = pitch;
                    }
                }

            }

            // ─── Mover per-frame positioning ─────────────────────────────────
            // Server mover requestTeleport runs at 20 Hz; without client
            // prediction the cam stutters one tick behind the player when the
            // player flies fast. For any camera that the LOCAL player is moving
            // (synced via CameraEntity.MOVER_PLAYER + MOVER_DISTANCE), or that
            // the local client-only mover / EditCameraScreen Move mode is
            // driving, position the cam at lerped(player.pos) + lerped(rotVec)
            // * distance every frame.
            if (mc.player != null) {
                double px = net.minecraft.util.math.MathHelper.lerp(tickDelta, mc.player.lastRenderX, mc.player.getX());
                double py = net.minecraft.util.math.MathHelper.lerp(tickDelta, mc.player.lastRenderY, mc.player.getY());
                double pz = net.minecraft.util.math.MathHelper.lerp(tickDelta, mc.player.lastRenderZ, mc.player.getZ());
                float  pyaw   = net.minecraft.util.math.MathHelper.lerp(tickDelta, mc.player.lastYaw, mc.player.getYaw());
                float  ppitch = net.minecraft.util.math.MathHelper.lerp(tickDelta, mc.player.lastPitch, mc.player.getPitch());
                float yawRad   = pyaw   * 0.017453292f;
                float pitchRad = ppitch * 0.017453292f;
                double dirX = -Math.sin(yawRad) * Math.cos(pitchRad);
                double dirY = -Math.sin(pitchRad);
                double dirZ =  Math.cos(yawRad) * Math.cos(pitchRad);
                java.util.UUID playerUuid = mc.player.getUuid();

                java.util.function.BiConsumer<CameraEntity, Double> place = (cam, distance) -> {
                    double tx = px + dirX * distance;
                    double ty = py + dirY * distance;
                    double tz = pz + dirZ * distance;
                    cam.setPosition(tx, ty, tz);
                    cam.lastRenderX = tx; cam.lastRenderY = ty; cam.lastRenderZ = tz;
                    cam.lastX = tx; cam.lastY = ty; cam.lastZ = tz;
                };

                // Server-side mover (camera synced from server with MOVER_PLAYER set)
                for (CameraEntity cam : allCameras) {
                    java.util.UUID moverPlayer = cam.getMoverPlayerUuid();
                    if (moverPlayer != null && moverPlayer.equals(playerUuid)) {
                        place.accept(cam, (double) cam.getMoverDistance());
                    }
                }

                // Client-only mover (server doesn't know about these cameras)
                if (clientMoverActive && clientMoverCamUuid != null) {
                    CameraEntity cam = CLIENT_CAMERAS.get(clientMoverCamUuid);
                    if (cam != null) place.accept(cam, clientMoverDistance);
                }

                // EditCameraScreen Move mode for client-only cameras (no server loop)
                if (editMoveCamUuid != null && !editMoveServerStarted) {
                    CameraEntity cam = CLIENT_CAMERAS.get(editMoveCamUuid);
                    if (cam != null) place.accept(cam, editMoveDistance);
                }
            }
        };

        // ENTITY_UNLOAD also clears from CLIENT_CAMERAS
        // (fires if we ever explicitly add client cameras to the world)

        // Keep cursor unlocked when viewing through a camera
        // Clear zoomer target when player releases shift
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Attached-camera restore: count down after join before spawning cameras,
            // giving the server time to put the player at their real spawn position.
            if (restoreTickDelay > 0 && client.player != null && client.world != null) {
                if (--restoreTickDelay == 0) {
                    java.util.UUID playerId = client.player.getUuid();
                    for (PendingCamera pc : pendingCameraRestore) {
                        CameraEntity cam = new CameraEntity(Cameramod.CAMERA_ENTITY_ENTITY_TYPE, client.world);
                        cam.refreshPositionAndAngles(
                            client.player.getX() + pc.x(),
                            client.player.getY() + pc.y(),
                            client.player.getZ() + pc.z(),
                            pc.yaw(), pc.pitch());
                        cam.setClientOnly(true);
                        // Restore the saved UUID so the camera keeps its identity:
                        // its TRACKED_CAMERAS entry (name + per-cam settings) matches
                        // again, instead of orphaning the old entry under a fresh
                        // random UUID. setUuid must run before addEntity so the world
                        // indexes it correctly.
                        if (pc.uuid() != null) cam.setUuid(pc.uuid());
                        if (pc.name() != null && !pc.name().isEmpty()) {
                            cam.setCustomName(net.minecraft.text.Text.literal(pc.name()));
                            cam.setCustomNameVisible(true);
                        }
                        cam.setAttachTargetUuid(playerId);
                        cam.setAttachOffset(new net.minecraft.util.math.Vec3d(pc.x(), pc.y(), pc.z()));
                        cam.setAttachMode(pc.attachMode());
                        cam.setGravityEnabled(pc.gravityEnabled());
                        cam.setFixedTargetUuid(pc.fixedTargetUuid());
                        cam.setFixerMode(pc.fixerMode());
                        cam.setId(nextClientCameraId());
                        CLIENT_CAMERAS.put(cam.getUuid(), cam);
                        // Ensure the camera shows up in the Cameras tab with its
                        // restored name (creates the tracked entry if disk load
                        // didn't, refreshes the cached name if it did).
                        addTrackedCamera(cam.getUuid());
                        // Add the entity to the world so it's a real client-side
                        // entity (3D model renders, ticks, etc.). The ENTITY_UNLOAD
                        // handler keeps it in CLIENT_CAMERAS even if the world manager
                        // evicts it later, so streaming/attachment stay robust.
                        ((net.minecraft.client.world.ClientWorld) client.world).addEntity(cam);
                        if (pc.streaming()) {
                            CameraRenderer.setBoundCamera(cam.getUuid());
                            CameraRenderer.setStreamingEnabled(true);
                        } else if (pc.wasBound()) {
                            CameraRenderer.setBoundCamera(cam.getUuid());
                        }
                    }
                    pendingCameraRestore.clear();
                }
            }

            if (isCamera && client.mouse != null) {
                client.mouse.unlockCursor();
            }

            // Deferred edit-screen open: wait until the just-spawned camera's
            // entity exists client-side so its position/rotation are populated.
            if (pendingEditScreenCamUuid != null) {
                CameraEntity cam = findCameraByUuid(pendingEditScreenCamUuid, client);
                boolean timedOut = --pendingEditScreenTimeout <= 0;
                if (cam != null || timedOut) {
                    java.util.UUID id = pendingEditScreenCamUuid;
                    pendingEditScreenCamUuid = null;
                    if (TRACKED_CAMERAS.containsKey(id)) {
                        client.setScreen(new dev.tggamesyt.cameramod.client.gui.EditCameraScreen(
                                new dev.tggamesyt.cameramod.client.gui.CameraGuiScreen(
                                        dev.tggamesyt.cameramod.client.gui.CameraGuiScreen.Tab.CAMERAS),
                                id));
                    }
                }
            }

            // Track the most recent camera attached to the player. Picks up
            // attachments made via any means (Attacher item, Edit screen,
            // restore-on-join, sidebar button) without needing each path to
            // update the field itself.
            if (client.player != null) {
                java.util.UUID pid = client.player.getUuid();
                localPlayerUuid = pid;   // cached for the DISCONNECT capture
                CameraEntity found = findCameraAttachedTo(pid, client);
                if (found != null) {
                    lastAttachedToPlayerCameraUuid = found.getUuid();
                    // Guarantee any camera attached to the player shows up in the
                    // camera list — otherwise there's no UI to detach/remove it
                    // and the attachment becomes impossible to get rid of.
                    addTrackedCamera(found.getUuid());
                }
            }
            if (zoomerActive && client.player != null && !client.player.isSneaking()) {
                zoomerActive = false;
                if (serverHasMod) ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 2, 0f));
                else {
                    clientZoomerCamUuid = null;
                    zoomerActive = false;
                }
            }

            // Camera mode keybind: tap toggles camera mode, hold (>=300ms) opens
            // the camera GUI on the settings tab. We do edge detection via
            // isPressed() so we can distinguish tap from hold; wasPressed() is
            // drained so its queued press events don't double-fire anything.
            if (cameraModeKey != null) {
                boolean inGame = client.world != null && client.player != null;
                // In-game, isPressed() tracks the keybind. Out-of-game a screen
                // (TitleScreen, etc.) consumes the key event before KeyBinding
                // state updates, so isPressed() never flips — poll the physical
                // key directly via GLFW instead.
                boolean down = inGame ? cameraModeKey.isPressed()
                                      : isCameraKeyPhysicallyDown(client);
                long now = System.nanoTime();
                boolean canRespond = client.currentScreen == null;
                if (!inGame) {
                    // Out-of-game (title screen, server list, "Connecting…", etc.):
                    // tap toggles streaming so the camera feed mirrors whatever
                    // menu is currently on screen (or shows the off-image when
                    // disabled). Independent of the in-game POV/hotbar logic —
                    // no hold action here, no camera-gui-mode branching.
                    if (f9LockedOutUntilRelease) {
                        if (!down) f9LockedOutUntilRelease = false;
                    } else if (!down && f9WasDown) {
                        CameraRenderer.clearBoundCamera();
                        // Toggle the MENU-only streaming flag, not the in-game one,
                        // so this choice persists across a world session instead of
                        // being overwritten by in-game streaming state.
                        boolean nowOn = !CameraRenderer.isMenuStreamingEnabled();
                        CameraRenderer.setMenuStreamingEnabled(nowOn);
                        // Out-of-game there's no HUD to reflect the change, so a
                        // toast is the only feedback the toggle happened. show()
                        // (vs add()) reuses the same toast slot so repeated taps
                        // update in place instead of stacking.
                        net.minecraft.client.toast.SystemToast.show(
                                client.getToastManager(),
                                net.minecraft.client.toast.SystemToast.Type.PERIODIC_NOTIFICATION,
                                net.minecraft.text.Text.translatable("cameramod.toast.streaming.title"),
                                net.minecraft.text.Text.translatable(nowOn
                                        ? "cameramod.toast.streaming.on"
                                        : "cameramod.toast.streaming.off"));
                    }
                } else if (canRespond) {
                    if (f9LockedOutUntilRelease) {
                        // A screen just closed via F9 — swallow the still-held
                        // press so we don't immediately reopen it on this tick.
                        // Clears as soon as the user lets go of F9.
                        if (!down) f9LockedOutUntilRelease = false;
                    } else {
                        if (down && !f9WasDown) {
                            f9PressStartNanos = now;
                            f9HoldFired = false;
                        } else if (!down && f9WasDown && !f9HoldFired) {
                            if (CameraRenderer.getCameraGuiMode() && !cameraMode) {
                                client.setScreen(new dev.tggamesyt.cameramod.client.gui.CameraGuiScreen(
                                        dev.tggamesyt.cameramod.client.gui.CameraGuiScreen.Tab.CAMERAS));
                            } else {
                                cameraMode = !cameraMode;
                            }
                        }
                        if (down && !f9HoldFired
                                && (now - f9PressStartNanos) >= F9_HOLD_THRESHOLD_NANOS) {
                            f9HoldFired = true;
                            client.setScreen(new dev.tggamesyt.cameramod.client.gui.CameraGuiScreen(
                                    dev.tggamesyt.cameramod.client.gui.CameraGuiScreen.Tab.SETTINGS, true));
                            // Don't let the release edge fire a tap on top of the hold.
                            down = false;
                        }
                    }
                } else {
                    // Screen is open — freeze hold-fire so a long press doesn't re-trigger,
                    // and let f9WasDown sync to the physical key state below so a F9 that's
                    // still held when the screen closes doesn't synthesize a fresh press.
                    f9HoldFired = true;
                }
                while (cameraModeKey.wasPressed()) { /* drain queued presses */ }
                f9WasDown = down;
            }

            // Re-add client-only cameras that were evicted from the world entity list
            // (chunk unloads mark them as removed but we keep them in CLIENT_CAMERAS).
            // The 3D model and entity ticking require the entity to be in the world.
            if (client.world != null) {
                for (CameraEntity cam : CLIENT_CAMERAS.values()) {
                    if (cam.isClientOnly() && cam.isRemoved()) {
                        // DISCARDED (vs chunk-unload eviction) means something else
                        // claimed our numeric entity ID: ClientWorld.addEntity()
                        // discards the resident entity when a new one arrives with
                        // the same ID (e.g. a plugin's packet-entity using its own
                        // negative-ID counter). Re-adding under the same ID would
                        // just discard the other entity and start a tug-of-war —
                        // the camera flickers out every refresh and the foreign
                        // entity's packets corrupt its position/rotation. Take a
                        // fresh ID instead; nothing references client-only cameras
                        // by numeric ID, so the swap is invisible to the rest of
                        // the mod (all lookups are UUID-based).
                        if (cam.getRemovalReason() == Entity.RemovalReason.DISCARDED) {
                            cam.setId(nextClientCameraId());
                        }
                        cam.resetRemoval();
                        ((net.minecraft.client.world.ClientWorld) client.world).addEntity(cam);
                    }
                }
            }

            // Mover position updates run per-frame in WorldRenderEvents.START
            // above so the cam keeps up with the player when flying fast (the
            // 20Hz tick was visibly stuttering).
        });
    }

    /**
     * Physical state of the camera-mode keybind's bound key, polled straight
     * from GLFW. Needed out-of-game: at the title screen / menus, the open
     * Screen consumes key events before KeyBinding state is updated, so
     * {@code cameraModeKey.isPressed()} stays false. GLFW reflects raw hardware
     * state regardless of event consumption. Honors a rebound key (keyboard or
     * mouse button) via Fabric's KeyBindingHelper.
     */
    private static boolean isCameraKeyPhysicallyDown(MinecraftClient client) {
        if (cameraModeKey == null || client.getWindow() == null) return false;
        net.minecraft.client.util.InputUtil.Key key =
                net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(cameraModeKey);
        if (key == null) return false;
        long handle = client.getWindow().getHandle();
        int code = key.getCode();
        return switch (key.getCategory()) {
            case KEYSYM -> code != GLFW.GLFW_KEY_UNKNOWN
                    && net.minecraft.client.util.InputUtil.isKeyPressed(client.getWindow(), code);
            case MOUSE  -> GLFW.glfwGetMouseButton(handle, code) == GLFW.GLFW_PRESS;
            default     -> false;
        };
    }

    // ==================== Camera Mode Interaction ====================

    /**
     * Called by Fabric interaction events when the player right-clicks in camera mode.
     * Dispatches to server (if mod present) or client simulation.
     * @param slot  selected hotbar slot (0-7 = camera tool)
     * @param entity clicked entity, or null
     * @param blockHit clicked block, or null
     */
    public static void onCameraItemInteract(int slot, Entity entity, BlockHitResult blockHit) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || slot < 0 || slot >= CAMERA_HOTBAR_STACKS.length) return;
        boolean sneaking = mc.player.isSneaking();

        if (serverHasMod) {
            dispatchToServer(slot, entity, blockHit, sneaking, mc);
        } else {
            simulateClientSide(slot, entity, blockHit, sneaking, mc);
        }
    }

    private static void dispatchToServer(int slot, Entity entity, BlockHitResult blockHit, boolean sneaking, MinecraftClient mc) {
        java.util.UUID nilUuid = CameraServerThing.CameraItemUseC2SPayload.NIL;
        java.util.UUID camUuid = nilUuid;
        java.util.UUID targetUuid = nilUuid;
        byte actionType = 0;
        int bx = 0, by = 0, bz = 0;
        byte face = 0;

        if (entity instanceof CameraEntity cam) {
            camUuid = cam.getUuid();
        }

        switch (slot) {
            case 0 -> { // Camera item — place
                if (blockHit != null) {
                    net.minecraft.util.math.BlockPos pos = blockHit.getBlockPos();
                    bx = pos.getX(); by = pos.getY(); bz = pos.getZ();
                    face = (byte) blockHit.getSide().ordinal();
                    actionType = 0;
                } else return; // can't place in air
            }
            case 1 -> { // Activator
                if (entity instanceof CameraEntity) { actionType = 1; }
                else { actionType = 0; camUuid = nilUuid; }
            }
            case 2 -> { // Orienter
                // camUuid already set from entity; if air-click use bound camera
                if (!(entity instanceof CameraEntity)) camUuid = nilUuid;
            }
            case 3 -> { // Mover
                if (moverActive) { actionType = 1; } // stop
                else if (entity instanceof CameraEntity) { actionType = 0; } // start on clicked cam
                else return;
            }
            case 4 -> { // Fixer
                if (entity instanceof CameraEntity cam) {
                    if (sneaking) {
                        clientSelectedCamera = cam.getUuid();
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera selected — click entity to fix to"), true);
                        return;
                    }
                    actionType = 0; // toggle fix-to-self
                } else if (clientSelectedCamera != null) {
                    camUuid = clientSelectedCamera;
                    targetUuid = entity != null ? entity.getUuid() : nilUuid;
                    actionType = entity != null ? (byte)1 : (byte)3; // fix to target OR clear
                    clientSelectedCamera = null;
                } else {
                    // air-click: toggle fixer mode on bound camera
                    java.util.UUID boundUuid = CameraRenderer.getBoundCameraUuid();
                    if (boundUuid == null) return;
                    camUuid = boundUuid; actionType = 3;
                }
            }
            case 5 -> { // Zoomer
                if (entity instanceof CameraEntity cam) {
                    if (sneaking) { actionType = 0; } // set zoom target
                    else return;
                } else {
                    actionType = 1; // clear zoom target
                    camUuid = nilUuid;
                }
            }
            case 6 -> { // Gravity
                if (!(entity instanceof CameraEntity)) return;
                actionType = 0;
            }
            case 7 -> { // Attacher
                if (entity instanceof CameraEntity cam) {
                    if (sneaking) {
                        clientSelectedCamera = cam.getUuid();
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera selected — click entity to attach to"), true);
                        return;
                    }
                    actionType = 0; // toggle attach to self
                } else if (clientSelectedCamera != null) {
                    camUuid = clientSelectedCamera;
                    targetUuid = entity != null ? entity.getUuid() : nilUuid;
                    actionType = 1;
                    clientSelectedCamera = null;
                } else if (sneaking) {
                    // air-click shift: toggle attach mode on bound cam
                    java.util.UUID boundUuid = CameraRenderer.getBoundCameraUuid();
                    if (boundUuid == null) return;
                    camUuid = boundUuid; actionType = 3;
                } else return;
            }
            case 8 -> { // Remover
                if (entity instanceof CameraEntity cam) {
                    camUuid = cam.getUuid();
                } else return;
            }
            default -> { return; }
        }
        ClientPlayNetworking.send(new CameraServerThing.CameraItemUseC2SPayload(
                (byte) slot, actionType, camUuid, targetUuid, bx, by, bz, face));
    }

    private static void simulateClientSide(int slot, Entity entity, BlockHitResult blockHit, boolean sneaking, MinecraftClient mc) {
        switch (slot) {
            case 0 -> { // Camera item — place
                if (blockHit != null) {
                    net.minecraft.util.math.BlockPos spawnPos = blockHit.getBlockPos().offset(blockHit.getSide());
                    CameraEntity cam = new CameraEntity(Cameramod.CAMERA_ENTITY_ENTITY_TYPE, mc.world);
                    cam.refreshPositionAndAngles(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, mc.player.getYaw(), 0);
                    cam.setClientOnly(true);
                    cam.setId(nextClientCameraId());
                    CLIENT_CAMERAS.put(cam.getUuid(), cam);
                    ((net.minecraft.client.world.ClientWorld) mc.world).addEntity(cam);
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Camera placed (client)"), true);
                }
            }
            case 1 -> { // Activator (mirrors server-side CameraActivatorItem.use)
                if (entity instanceof CameraEntity cam && CLIENT_CAMERAS.containsKey(cam.getUuid())) {
                    java.util.UUID bound = CameraRenderer.getBoundCameraUuid();
                    if (cam.getUuid().equals(bound)) {
                        CameraRenderer.clearBoundCamera();
                        CameraRenderer.setStreamingEnabled(false);
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera unbound"), true);
                    } else {
                        CameraRenderer.setBoundCamera(cam.getUuid());
                        CameraRenderer.setStreamingEnabled(true);
                        addTrackedCamera(cam.getUuid());
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera bound"), true);
                    }
                } else {
                    // Air or non-camera click: toggle streaming. Falls back to player POV when no
                    // camera is bound — same behavior as server-side activator.
                    if (CameraRenderer.isStreamingEnabled()) {
                        CameraRenderer.setStreamingEnabled(false);
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Streaming disabled"), true);
                    } else {
                        CameraRenderer.setStreamingEnabled(true);
                        java.util.UUID bound = CameraRenderer.getBoundCameraUuid();
                        if (bound == null) {
                            mc.player.sendMessage(net.minecraft.text.Text.literal("Streaming enabled (player POV)"), true);
                        } else {
                            mc.player.sendMessage(net.minecraft.text.Text.literal("Streaming enabled"), true);
                        }
                    }
                }
            }
            case 2 -> { // Orienter
                CameraEntity cam = (entity instanceof CameraEntity ce) ? ce : getBoundClientCamera();
                if (cam != null) {
                    cam.setYaw(mc.player.getYaw()); cam.setPitch(mc.player.getPitch());
                    cam.setHeadYaw(mc.player.getYaw()); cam.setBodyYaw(mc.player.getYaw());
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Camera oriented"), true);
                }
            }
            case 3 -> { // Mover
                if (clientMoverActive) {
                    clientMoverActive = false;
                    if (clientMoverCamUuid != null) {
                        CameraEntity cam2 = CLIENT_CAMERAS.get(clientMoverCamUuid);
                        if (cam2 != null) cam2.setBeingMoved(false);
                    }
                    clientMoverCamUuid = null;
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Camera mover stopped"), true);
                } else if (entity instanceof CameraEntity cam3 && CLIENT_CAMERAS.containsKey(cam3.getUuid())) {
                    clientMoverCamUuid = cam3.getUuid();
                    clientMoverDistance = cam3.getEntityPos().distanceTo(mc.player.getEntityPos());
                    clientMoverActive = true;
                    cam3.setBeingMoved(true);
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Camera mover started"), true);
                }
            }
            case 4 -> { // Fixer
                // Check pending selection FIRST (matches server order: selection → entity type → air)
                if (clientSelectedCamera != null) {
                    CameraEntity cam5 = CLIENT_CAMERAS.get(clientSelectedCamera);
                    clientSelectedCamera = null;
                    if (cam5 != null && entity != null && !(entity instanceof CameraEntity)) {
                        cam5.setFixedTargetUuid(entity.getUuid());
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera fixed to " + entity.getName().getString()), true);
                    } else {
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera selection cleared"), true);
                    }
                } else if (entity instanceof CameraEntity cam4 && CLIENT_CAMERAS.containsKey(cam4.getUuid())) {
                    if (sneaking) {
                        clientSelectedCamera = cam4.getUuid();
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera selected — click entity to fix to"), true);
                    } else {
                        java.util.UUID cur = cam4.getFixedTargetUuid();
                        if (mc.player.getUuid().equals(cur)) { cam4.setFixedTargetUuid(null); mc.player.sendMessage(net.minecraft.text.Text.literal("Tracking disabled"), true); }
                        else { cam4.setFixedTargetUuid(mc.player.getUuid()); mc.player.sendMessage(net.minecraft.text.Text.literal("Camera tracks you"), true); }
                    }
                } else if (sneaking) {
                    // Shift+air: toggle fixer mode on bound camera
                    CameraEntity cam6 = getBoundClientCamera();
                    if (cam6 != null) {
                        byte newMode = (byte) ((cam6.getFixerMode() + 1) % 2);
                        cam6.setFixerMode(newMode);
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Fixer mode: " + (newMode == 0 ? "Look At" : "Look Same Way")), true);
                    }
                }
            }
            case 5 -> { // Zoomer
                if (entity instanceof CameraEntity cam7 && sneaking && CLIENT_CAMERAS.containsKey(cam7.getUuid())) {
                    clientZoomerCamUuid = cam7.getUuid(); zoomerActive = true;
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Zoom target set"), true);
                } else if (!(entity instanceof CameraEntity) && clientZoomerCamUuid != null) {
                    // Only clear zoom when NOT clicking a camera (non-sneaking camera click = no-op, matching server PASS)
                    clientZoomerCamUuid = null; zoomerActive = false;
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Zoom cleared"), true);
                }
            }
            case 6 -> { // Gravity
                if (entity instanceof CameraEntity cam8 && CLIENT_CAMERAS.containsKey(cam8.getUuid())) {
                    boolean ng = !cam8.isGravityEnabled();
                    cam8.setGravityEnabled(ng);
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Gravity " + (ng ? "ON" : "OFF") + " (physics not supported client-side)"), true);
                }
            }
            case 7 -> { // Attacher
                // Check pending selection FIRST (matches server order: selection → entity type → air)
                if (clientSelectedCamera != null) {
                    CameraEntity cam10 = CLIENT_CAMERAS.get(clientSelectedCamera);
                    clientSelectedCamera = null;
                    if (cam10 != null && entity != null && !(entity instanceof CameraEntity)) {
                        cam10.setAttachTargetUuid(entity.getUuid());
                        cam10.setAttachOffset(cam10.getEntityPos().subtract(entity.getEntityPos()));
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera attached to " + entity.getName().getString()), true);
                    } else {
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera selection cleared"), true);
                    }
                } else if (entity instanceof CameraEntity cam9 && CLIENT_CAMERAS.containsKey(cam9.getUuid())) {
                    if (sneaking) {
                        clientSelectedCamera = cam9.getUuid();
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Camera selected — click entity to attach to"), true);
                    } else {
                        java.util.UUID cur = cam9.getAttachTargetUuid();
                        if (mc.player.getUuid().equals(cur)) {
                            cam9.setAttachTargetUuid(null);
                            mc.player.sendMessage(net.minecraft.text.Text.literal("Camera detached"), true);
                        } else {
                            boolean wasDetached = cur == null;
                            cam9.setAttachTargetUuid(mc.player.getUuid());
                            if (cam9.getAttachMode() == 1 && wasDetached) {
                                // stored local-space orbit offset is already correct
                            } else {
                                net.minecraft.util.math.Vec3d delta = cam9.getEntityPos().subtract(mc.player.getEntityPos());
                                if (cam9.getAttachMode() == 1) {
                                    float yr = (float)(mc.player.getYaw() * Math.PI / 180.0);
                                    double lx =  delta.x * Math.cos(yr) + delta.z * Math.sin(yr);
                                    double lz = -delta.x * Math.sin(yr) + delta.z * Math.cos(yr);
                                    cam9.setAttachOffset(new net.minecraft.util.math.Vec3d(lx, delta.y, lz));
                                } else {
                                    cam9.setAttachOffset(delta);
                                }
                            }
                            mc.player.sendMessage(net.minecraft.text.Text.literal("Camera attached to you"), true);
                        }
                    }
                } else if (sneaking) {
                    // Shift+air-click: toggle attach mode on bound camera.
                    // Must convert the stored offset to the new coordinate space so
                    // the camera stays at its current world position after the switch.
                    CameraEntity cam11 = getBoundClientCamera();
                    if (cam11 != null) {
                        byte newMode = (byte) ((cam11.getAttachMode() + 1) % 2);
                        net.minecraft.util.math.Vec3d off = cam11.getAttachOffset();
                        java.util.UUID atu = cam11.getAttachTargetUuid();
                        Entity attachTarget = null;
                        if (mc.player != null && mc.player.getUuid().equals(atu)) {
                            attachTarget = mc.player;
                        } else if (atu != null && mc.world != null) {
                            for (Entity e : mc.world.getEntities()) {
                                if (e.getUuid().equals(atu)) { attachTarget = e; break; }
                            }
                        }
                        if (attachTarget != null) {
                            float yr = (float) (attachTarget.getYaw() * Math.PI / 180.0);
                            if (newMode == 1) {
                                // world-space → local-space: rotate by -yaw
                                double lx =  off.x * Math.cos(yr) + off.z * Math.sin(yr);
                                double lz = -off.x * Math.sin(yr) + off.z * Math.cos(yr);
                                cam11.setAttachOffset(new net.minecraft.util.math.Vec3d(lx, off.y, lz));
                            } else {
                                // local-space → world-space: rotate by +yaw
                                double wx = off.x * Math.cos(yr) - off.z * Math.sin(yr);
                                double wz = off.x * Math.sin(yr) + off.z * Math.cos(yr);
                                cam11.setAttachOffset(new net.minecraft.util.math.Vec3d(wx, off.y, wz));
                            }
                        }
                        cam11.setAttachMode(newMode);
                        mc.player.sendMessage(net.minecraft.text.Text.literal("Attach mode: " + (newMode == 0 ? "Fixed" : "Orbit")), true);
                    }
                }
            }
            case 8 -> { // Remover
                if (entity instanceof CameraEntity cam12 && CLIENT_CAMERAS.containsKey(cam12.getUuid())) {
                    java.util.UUID camId = cam12.getUuid();
                    if (camId.equals(CameraRenderer.getBoundCameraUuid())) {
                        CameraRenderer.clearBoundCamera();
                        CameraRenderer.setStreamingEnabled(false);
                    }
                    CLIENT_CAMERAS.remove(camId);
                    cam12.discard();
                    mc.player.sendMessage(net.minecraft.text.Text.literal("Camera removed"), true);
                }
            }
        }
    }

    /** Returns the client camera bound to the stream, or first available client camera. */
    private static CameraEntity getBoundClientCamera() {
        java.util.UUID bound = CameraRenderer.getBoundCameraUuid();
        if (bound != null) {
            CameraEntity cam = CLIENT_CAMERAS.get(bound);
            if (cam != null) return cam;
        }
        return CLIENT_CAMERAS.isEmpty() ? null : CLIENT_CAMERAS.values().iterator().next();
    }

    /** Client-side mover scroll (no server). */
    public static void onClientMoverScroll(float delta) {
        clientMoverDistance = Math.max(1.0, clientMoverDistance + delta * 0.5);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("Distance: " + String.format("%.1f", clientMoverDistance)), true);
    }

    // ─── EditCameraScreen-driven external interactions ───────────────────────

    /** Closes EditCameraScreen and enters move mode for {@code camUuid}. */
    public static void enterEditMoveMode(java.util.UUID camUuid) {
        if (camUuid == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        editMoveCamUuid       = camUuid;
        editMoveServerStarted = false;

        // Seed distance from current camera position so the camera doesn't snap.
        CameraEntity cam = findAnyCamera(mc, camUuid);
        if (cam != null && mc.player != null) {
            editMoveDistance = Math.max(1.0, cam.getEntityPos().distanceTo(mc.player.getEntityPos()));
        }

        if (serverHasMod && cam != null && !cam.isClientOnly()) {
            // Reuse the server-side camera_mover state — it moves the camera
            // each server tick using player look + distance. The S2C state
            // packet flips moverActive=true on arrival.
            java.util.UUID nil = CameraServerThing.CameraItemUseC2SPayload.NIL;
            ClientPlayNetworking.send(new CameraServerThing.CameraItemUseC2SPayload(
                    (byte) 3, (byte) 0, camUuid, nil, 0, 0, 0, (byte) 0));
            editMoveServerStarted = true;
        }
        mc.setScreen(null);
    }

    /** Stop the move mode and reopen EditCameraScreen for the same camera. */
    public static void exitEditMoveMode() {
        java.util.UUID camUuid = editMoveCamUuid;
        if (camUuid == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (editMoveServerStarted) {
            java.util.UUID nil = CameraServerThing.CameraItemUseC2SPayload.NIL;
            ClientPlayNetworking.send(new CameraServerThing.CameraItemUseC2SPayload(
                    (byte) 3, (byte) 1, camUuid, nil, 0, 0, 0, (byte) 0));
        }
        editMoveCamUuid       = null;
        editMoveServerStarted = false;
        mc.setScreen(new dev.tggamesyt.cameramod.client.gui.EditCameraScreen(null, camUuid));
    }

    /** Adjust the move-mode distance. Called from MouseMixin on scroll. */
    public static void onEditMoveScroll(float delta) {
        editMoveDistance = Math.max(1.0, editMoveDistance + delta * 0.5);
        if (editMoveServerStarted) {
            // Keep server-side distance in sync via the existing scroll packet.
            ClientPlayNetworking.send(new CameraServerThing.CameraScrollC2SPayload((byte) 0, delta));
        }
    }

    /**
     * Closes EditCameraScreen and waits for the player to click an entity which
     * will be set as the camera's fixer ({@code kind=1}) or attach ({@code kind=2}) target.
     */
    public static void enterEditSelectMode(java.util.UUID camUuid, int kind) {
        if (camUuid == null || (kind != 1 && kind != 2)) return;
        editSelectCamUuid = camUuid;
        editSelectKind    = kind;
        MinecraftClient.getInstance().setScreen(null);
    }

    /** Apply the selected target to the camera and reopen EditCameraScreen. */
    public static void applyEditSelectTarget(Entity target) {
        java.util.UUID camUuid = editSelectCamUuid;
        int kind = editSelectKind;
        if (camUuid == null || target == null || target instanceof CameraEntity) {
            cancelEditSelectMode();
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        CameraEntity cam = findAnyCamera(mc, camUuid);
        if (cam != null && cam.isClientOnly()) {
            if (kind == 1) {
                cam.setFixedTargetUuid(target.getUuid());
            } else {
                cam.setAttachTargetUuid(target.getUuid());
                cam.setAttachOffset(cam.getEntityPos().subtract(target.getEntityPos()));
            }
        } else if (serverHasMod) {
            java.util.UUID nil = CameraServerThing.CameraItemUseC2SPayload.NIL;
            byte slot = (byte) (kind == 1 ? 4 : 7);
            ClientPlayNetworking.send(new CameraServerThing.CameraItemUseC2SPayload(
                    slot, (byte) 1, camUuid, target.getUuid(), 0, 0, 0, (byte) 0));
        }
        editSelectCamUuid = null;
        editSelectKind    = 0;
        mc.setScreen(new dev.tggamesyt.cameramod.client.gui.EditCameraScreen(null, camUuid));
    }

    /** Cancel the select mode without setting a target. Reopen the GUI. */
    public static void cancelEditSelectMode() {
        java.util.UUID camUuid = editSelectCamUuid;
        editSelectCamUuid = null;
        editSelectKind    = 0;
        if (camUuid != null) {
            MinecraftClient.getInstance().setScreen(
                    new dev.tggamesyt.cameramod.client.gui.EditCameraScreen(null, camUuid));
        }
    }

    private static CameraEntity findAnyCamera(MinecraftClient mc, java.util.UUID uuid) {
        if (mc.world != null) {
            for (Entity e : mc.world.getEntities()) {
                if (e.getUuid().equals(uuid) && e instanceof CameraEntity cam) return cam;
            }
        }
        return CLIENT_CAMERAS.get(uuid);
    }

    /** Client-side zoomer scroll (no server). */
    public static void onClientZoomerScroll(float delta) {
        if (clientZoomerCamUuid == null) return;
        CameraEntity cam = CLIENT_CAMERAS.get(clientZoomerCamUuid);
        if (cam == null) return;
        float zoom = cam.getZoomLevel();
        boolean fine = zoom < 1.0f || (zoom == 1.0f && delta < 0);
        float step = fine ? 0.05f : 0.25f;
        zoom = Math.max(0.5f, Math.min(10.0f, zoom + delta * step));
        cam.setZoomLevel(zoom);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("Zoom: " + String.format("%.2f", zoom) + "x"), true);
    }

    /**
     * Record a camera as "ever activated" in the current session so it shows up
     * in the camera GUI's Cameras tab. Called on every bind (server-pushed or
     * client-simulated). No-op if the UUID is already tracked. Persisted lazily
     * — saved on disconnect along with the attached-camera state.
     */
    /** Find any camera (world or client-only) whose attach target is the given UUID. */
    public static CameraEntity findCameraAttachedTo(java.util.UUID targetUuid, MinecraftClient mc) {
        if (mc.world != null) {
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof CameraEntity cam && targetUuid.equals(cam.getAttachTargetUuid()))
                    return cam;
            }
        }
        for (CameraEntity cam : CLIENT_CAMERAS.values()) {
            if (targetUuid.equals(cam.getAttachTargetUuid())) return cam;
        }
        return null;
    }

    /** Look up a CameraEntity by UUID across both the world and the client-only map. */
    public static CameraEntity findCameraByUuid(java.util.UUID uuid, MinecraftClient mc) {
        if (uuid == null) return null;
        if (mc.world != null) {
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof CameraEntity cam && uuid.equals(cam.getUuid())) return cam;
            }
        }
        return CLIENT_CAMERAS.get(uuid);
    }

    /** True iff the lastAttached camera is currently attached to the local player. */
    public static boolean isLastAttachedCurrentlyAttached() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || lastAttachedToPlayerCameraUuid == null) return false;
        CameraEntity cam = findCameraByUuid(lastAttachedToPlayerCameraUuid, mc);
        return cam != null && mc.player.getUuid().equals(cam.getAttachTargetUuid());
    }

    /**
     * Cycle the attach mode (World ↔ Head) on the lastAttached camera. Mirrors
     * the Attacher item's shift+air-click. No-op if there is no lastAttached
     * camera or it is not currently attached to an entity.
     */
    public static void cycleAttachModeOnLastAttached() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || lastAttachedToPlayerCameraUuid == null) return;
        java.util.UUID camUuid = lastAttachedToPlayerCameraUuid;
        if (serverHasMod) {
            java.util.UUID nil = CameraServerThing.CameraItemUseC2SPayload.NIL;
            ClientPlayNetworking.send(new CameraServerThing.CameraItemUseC2SPayload(
                    (byte) 7, (byte) 3, camUuid, nil, 0, 0, 0, (byte) 0));
        } else {
            CameraEntity cam = findCameraByUuid(camUuid, mc);
            if (cam == null) return;
            java.util.UUID atu = cam.getAttachTargetUuid();
            if (atu == null) return;
            Entity attachTarget = mc.player.getUuid().equals(atu) ? mc.player
                    : findEntityByUuid(atu, mc);
            if (attachTarget == null) return;
            byte newMode = (byte) ((cam.getAttachMode() + 1) % 2);
            net.minecraft.util.math.Vec3d off = cam.getAttachOffset();
            float yr = (float) (attachTarget.getYaw() * Math.PI / 180.0);
            if (newMode == 1) {
                double lx =  off.x * Math.cos(yr) + off.z * Math.sin(yr);
                double lz = -off.x * Math.sin(yr) + off.z * Math.cos(yr);
                cam.setAttachOffset(new net.minecraft.util.math.Vec3d(lx, off.y, lz));
            } else {
                double wx = off.x * Math.cos(yr) - off.z * Math.sin(yr);
                double wz = off.x * Math.sin(yr) + off.z * Math.cos(yr);
                cam.setAttachOffset(new net.minecraft.util.math.Vec3d(wx, off.y, wz));
            }
            cam.setAttachMode(newMode);
        }
    }

    /**
     * Toggle the attach state of the lastAttached camera to the player.
     * If currently attached → detach. If not attached → attach (offset is
     * recomputed from the camera's current world position, so it stays put).
     */
    public static void toggleAttachLastToPlayer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || lastAttachedToPlayerCameraUuid == null) return;
        java.util.UUID camUuid = lastAttachedToPlayerCameraUuid;
        if (serverHasMod) {
            java.util.UUID nil = CameraServerThing.CameraItemUseC2SPayload.NIL;
            ClientPlayNetworking.send(new CameraServerThing.CameraItemUseC2SPayload(
                    (byte) 7, (byte) 0, camUuid, nil, 0, 0, 0, (byte) 0));
        } else {
            CameraEntity cam = findCameraByUuid(camUuid, mc);
            if (cam == null) return;
            if (mc.player.getUuid().equals(cam.getAttachTargetUuid())) {
                cam.setAttachTargetUuid(null);
            } else {
                boolean wasDetached = cam.getAttachTargetUuid() == null;
                cam.setAttachTargetUuid(mc.player.getUuid());
                // Head (Orbit) re-attach after a plain detach: the stored local-space
                // orbit offset is already the correct relative position. Re-using it
                // resumes the orbit near the player at the same radius instead of
                // keeping the camera at its old world position (possibly far away).
                // For a fresh first-time attach (prevTarget != null means target change,
                // or if was world-mode), compute the offset from current positions.
                if (cam.getAttachMode() == 1 && wasDetached) {
                    // stored offset is correct — nothing to do
                } else {
                    net.minecraft.util.math.Vec3d delta = cam.getEntityPos().subtract(mc.player.getEntityPos());
                    if (cam.getAttachMode() == 1) {
                        float yr = (float)(mc.player.getYaw() * Math.PI / 180.0);
                        double lx =  delta.x * Math.cos(yr) + delta.z * Math.sin(yr);
                        double lz = -delta.x * Math.sin(yr) + delta.z * Math.cos(yr);
                        cam.setAttachOffset(new net.minecraft.util.math.Vec3d(lx, delta.y, lz));
                    } else {
                        cam.setAttachOffset(delta);
                    }
                }
            }
        }
    }

    private static Entity findEntityByUuid(java.util.UUID uuid, MinecraftClient mc) {
        if (mc.world == null) return null;
        for (Entity e : mc.world.getEntities()) {
            if (uuid.equals(e.getUuid())) return e;
        }
        return null;
    }

    /**
     * Spawn a new camera at the player's current position, with their yaw and pitch.
     * If the player is flying, the camera spawns with gravity disabled so it stays put.
     * Called from the GUI sidebar "+" button. Returns the new camera's UUID so the
     * caller can immediately open its edit screen.
     */
    public static java.util.UUID spawnCameraAtPlayerFromGui() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return null;
        boolean flying = mc.player.getAbilities().flying;

        if (serverHasMod) {
            // Client picks the UUID so it can track the camera immediately
            // without waiting for the server's entity-spawn round-trip.
            java.util.UUID newUuid = java.util.UUID.randomUUID();
            java.util.UUID nil = CameraServerThing.CameraItemUseC2SPayload.NIL;
            ClientPlayNetworking.send(new CameraServerThing.CameraItemUseC2SPayload(
                    (byte) 0, (byte) 2, newUuid, nil,
                    0, 0, 0, (byte) (flying ? 1 : 0)));
            addTrackedCamera(newUuid);
            return newUuid;
        } else {
            CameraEntity cam = new CameraEntity(Cameramod.CAMERA_ENTITY_ENTITY_TYPE, mc.world);
            cam.refreshPositionAndAngles(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    mc.player.getYaw(), mc.player.getPitch());
            cam.setClientOnly(true);
            cam.setId(nextClientCameraId());
            if (flying) cam.setGravityEnabled(false);
            CLIENT_CAMERAS.put(cam.getUuid(), cam);
            ((net.minecraft.client.world.ClientWorld) mc.world).addEntity(cam);
            addTrackedCamera(cam.getUuid());
            return cam.getUuid();
        }
    }

    /**
     * Open EditCameraScreen for {@code camUuid} as soon as its entity exists
     * client-side. Used after the GUI "+" button spawns a camera so the edit
     * screen never opens before position/rotation are known. The actual open
     * happens in END_CLIENT_TICK.
     */
    public static void requestEditScreenWhenReady(java.util.UUID camUuid) {
        if (camUuid == null) return;
        pendingEditScreenCamUuid = camUuid;
        pendingEditScreenTimeout = 60; // ~3s @ 20 tps before giving up
    }

    public static void addTrackedCamera(java.util.UUID uuid) {
        if (uuid == null || TRACKED_CAMERAS.containsKey(uuid)) {
            // Only update the cached name if the entity has an actual custom name set.
            // If the entity is unloaded or hasn't synced its name yet, leave whatever
            // name was previously recorded so we don't revert to the fallback.
            TrackedCamera existing = TRACKED_CAMERAS.get(uuid);
            if (existing != null) {
                String custom = resolveCustomNameOnly(uuid);
                if (custom != null) existing.name = custom;
            }
            return;
        }
        TRACKED_CAMERAS.put(uuid, new TrackedCamera(uuid, resolveCameraName(uuid)));
    }

    public static void updateTrackedFrame(java.util.UUID uuid, byte[] frame, int w, int h) {
        TrackedCamera tc = TRACKED_CAMERAS.get(uuid);
        if (tc == null) return;
        // Reuse tc.frame — re-allocating this multi-MB array every frame churned
        // humongous objects and fragmented the heap.
        if (tc.frame == null || tc.frame.length != frame.length)
            tc.frame = new byte[frame.length];
        System.arraycopy(frame, 0, tc.frame, 0, frame.length);
        tc.frameW = w;
        tc.frameH = h;
        tc.frameVersion++;
    }

    /**
     * Best name we can attribute to a camera for save/snapshot: its entity
     * custom name if set, else its tracked-list name, else null (restore then
     * leaves the default). Used so attached cameras keep their name across
     * disconnect/reconnect and proxy sub-server hops.
     */
    private static String bestKnownName(CameraEntity cam) {
        if (cam.hasCustomName()) return cam.getCustomName().getString();
        TrackedCamera tc = TRACKED_CAMERAS.get(cam.getUuid());
        if (tc != null && tc.name != null && !tc.name.isEmpty()) return tc.name;
        return null;
    }

    private static String resolveCameraName(java.util.UUID uuid) {
        String custom = resolveCustomNameOnly(uuid);
        if (custom != null) return custom;
        // Sequential fallback: "Camera 1", "Camera 2", … based on how many
        // cameras are already tracked. This UUID is not yet in TRACKED_CAMERAS
        // when this is called for a new entry, so size()+1 is the next ordinal.
        return "Camera " + (TRACKED_CAMERAS.size() + 1);
    }

    /** Returns the entity's custom name string if one is set, else null. */
    private static String resolveCustomNameOnly(java.util.UUID uuid) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) {
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof CameraEntity && e.getUuid().equals(uuid)) {
                    return e.hasCustomName() ? e.getCustomName().getString() : null;
                }
            }
        }
        CameraEntity cam = CLIENT_CAMERAS.get(uuid);
        if (cam != null && cam.hasCustomName()) return cam.getCustomName().getString();
        return null;
    }

    /**
     * True when a tracked camera entity is currently loaded somewhere we can
     * point the edit GUI at (world entity list or client-only map). Tracked
     * entries whose entities are gone get filtered out of the Cameras tab.
     */
    public static boolean isTrackedCameraAlive(java.util.UUID uuid) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) {
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof CameraEntity && e.getUuid().equals(uuid)) return true;
            }
        }
        return CLIENT_CAMERAS.containsKey(uuid);
    }

    private static Path trackedSavePath(Path basePath) {
        return basePath.resolveSibling(basePath.getFileName().toString() + ".tracked");
    }

    private static Path trackedFramesDir(Path basePath) {
        return basePath.resolveSibling(basePath.getFileName().toString() + ".frames");
    }

    /** Save TRACKED_CAMERAS to the current world/server's tracked file, if any.
     *  Used to persist deletions immediately so they don't reappear on next join. */
    public static void saveCurrentTrackedState() {
        if (currentSavePath != null) saveTrackedCameras(currentSavePath);
    }

    static void saveTrackedCameras(Path basePath) {
        Path path = trackedSavePath(basePath);
        Properties props = new Properties();
        int i = 0;
        for (TrackedCamera tc : TRACKED_CAMERAS.values()) {
            props.setProperty(i + ".uuid", tc.uuid.toString());
            props.setProperty(i + ".name", tc.name == null ? "" : tc.name);
            if (tc.perCamFlipped        != null) props.setProperty(i + ".flip",  tc.perCamFlipped.toString());
            if (tc.perCamSeesChat       != null) props.setProperty(i + ".chat",  tc.perCamSeesChat.toString());
            if (tc.perCamNameTags       != null) props.setProperty(i + ".tags",  tc.perCamNameTags.toString());
            if (tc.perCamShowPlayerGuis != null) props.setProperty(i + ".pguis", tc.perCamShowPlayerGuis.toString());
            i++;
        }
        props.setProperty("count", String.valueOf(i));
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException ignored) {}
        try (OutputStream out = Files.newOutputStream(path)) {
            props.store(out, "cameramod tracked cameras");
        } catch (IOException e) {
            LOGGER.warn("Failed to save {}", path, e);
        }
        // Persist the last seen frame of each tracked camera as PNG so the
        // Cameras tab still shows something other than "No preview yet" on
        // the next session before live capture takes over.
        Path framesDir = trackedFramesDir(basePath);
        try { Files.createDirectories(framesDir); } catch (IOException ignored) {}
        for (TrackedCamera tc : TRACKED_CAMERAS.values()) {
            if (tc.frame == null) continue;
            int fw = tc.frameW > 0 ? tc.frameW : Cameramod.camwidth;
            int fh = tc.frameH > 0 ? tc.frameH : Cameramod.camheight;
            saveTrackedFrame(framesDir, tc.uuid, tc.frame, fw, fh);
        }
    }

    private static void saveTrackedFrame(Path framesDir, java.util.UUID uuid, byte[] frame, int w, int h) {
        if (frame == null || frame.length < w * h * 3) return;
        Path framePath = framesDir.resolve(uuid + ".png");
        try (net.minecraft.client.texture.NativeImage img =
                     new net.minecraft.client.texture.NativeImage(w, h, false)) {
            int rowLen = w * 3;
            for (int y = 0; y < h; y++) {
                int base = y * rowLen;
                for (int x = 0; x < w; x++) {
                    int i = base + x * 3;
                    int b = frame[i]     & 0xFF;
                    int g = frame[i + 1] & 0xFF;
                    int r = frame[i + 2] & 0xFF;
                    img.setColorArgb(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
                }
            }
            img.writeTo(framePath);
        } catch (Exception e) {
            LOGGER.warn("Failed to save tracked frame {}", framePath, e);
        }
    }

    private static void loadTrackedFrame(Path framesDir, TrackedCamera tc) {
        Path framePath = framesDir.resolve(tc.uuid + ".png");
        if (!Files.exists(framePath)) return;
        try (InputStream in = Files.newInputStream(framePath);
             net.minecraft.client.texture.NativeImage img =
                     net.minecraft.client.texture.NativeImage.read(in)) {
            int w = img.getWidth();
            int h = img.getHeight();
            byte[] frame = new byte[w * h * 3];
            int rowLen = w * 3;
            for (int y = 0; y < h; y++) {
                int base = y * rowLen;
                for (int x = 0; x < w; x++) {
                    int argb = img.getColorArgb(x, y);
                    int i = base + x * 3;
                    frame[i]     = (byte) (argb & 0xFF);
                    frame[i + 1] = (byte) ((argb >> 8) & 0xFF);
                    frame[i + 2] = (byte) ((argb >> 16) & 0xFF);
                }
            }
            // The Cameras tab now scales whatever resolution the saved
            // thumbnail was at (the preview path stores 640×360 frames so
            // unstarted cameras can show a card too), so accept any size.
            tc.frame = frame;
            tc.frameW = w;
            tc.frameH = h;
            tc.frameVersion = 1;
        } catch (Exception e) {
            LOGGER.warn("Failed to load tracked frame {}", framePath, e);
        }
    }

    private static void loadTrackedCameras(Path basePath) {
        Path path = trackedSavePath(basePath);
        Path framesDir = trackedFramesDir(basePath);
        if (!Files.exists(path)) return;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            int count = Integer.parseInt(props.getProperty("count", "0"));
            for (int i = 0; i < count; i++) {
                String uuidStr = props.getProperty(i + ".uuid");
                String name = props.getProperty(i + ".name", "");
                if (uuidStr == null) continue;
                try {
                    java.util.UUID id = java.util.UUID.fromString(uuidStr);
                    TrackedCamera tc = new TrackedCamera(id, name);
                    String flip  = props.getProperty(i + ".flip");
                    String chat  = props.getProperty(i + ".chat");
                    String tags  = props.getProperty(i + ".tags");
                    String pguis = props.getProperty(i + ".pguis");
                    if (flip  != null) tc.perCamFlipped        = Boolean.parseBoolean(flip);
                    if (chat  != null) tc.perCamSeesChat       = Boolean.parseBoolean(chat);
                    if (tags  != null) tc.perCamNameTags       = Boolean.parseBoolean(tags);
                    if (pguis != null) tc.perCamShowPlayerGuis = Boolean.parseBoolean(pguis);
                    if (Files.exists(framesDir)) loadTrackedFrame(framesDir, tc);
                    TRACKED_CAMERAS.put(id, tc);
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load {}", path, e);
        }
    }

    private static Path getConfigPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("cameramod-client.properties");
    }

    private static void loadClientConfig() {
        Path path = getConfigPath();
        if (!Files.exists(path)) return;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            String flip = props.getProperty("cameraFlipped");
            String chat = props.getProperty("cameraSeesChat");
            String tags = props.getProperty("cameraNameTags");
            String gui  = props.getProperty("cameraGuiMode");
            String pg   = props.getProperty("cameraShowPlayerGuis");
            String sfps = props.getProperty("cameraStreamFps");
            String vfps = props.getProperty("cameraVirtualFps");
            String hide = props.getProperty("hideCameraModels");
            String hideTags = props.getProperty("hideCameraNameTagsForPlayers");
            if (hide != null) CameraRenderer.setHideCameraModels(Boolean.parseBoolean(hide));
            if (hideTags != null) CameraRenderer.setHideCameraNameTagsForPlayers(Boolean.parseBoolean(hideTags));
            if (flip != null) CameraRenderer.setLocalFlipped(Boolean.parseBoolean(flip));
            if (chat != null) CameraRenderer.setLocalSeesChat(Boolean.parseBoolean(chat));
            if (tags != null) CameraRenderer.setLocalNameTags(Boolean.parseBoolean(tags));
            if (gui  != null) CameraRenderer.setLocalGuiMode(Boolean.parseBoolean(gui));
            if (pg   != null) CameraRenderer.setLocalShowPlayerGuis(Boolean.parseBoolean(pg));
            if (sfps != null) try { CameraRenderer.setLocalStreamFps(Integer.parseInt(sfps));  } catch (NumberFormatException ignored) {}
            if (vfps != null) try { CameraRenderer.setLocalVirtualFps(Integer.parseInt(vfps)); } catch (NumberFormatException ignored) {}
        } catch (IOException e) {
            LOGGER.warn("Failed to load cameramod-client.properties", e);
        }
    }

    public static void saveClientConfig() {
        Path path = getConfigPath();
        Properties props = new Properties();
        Boolean flip = CameraRenderer.getLocalFlipped();
        Boolean chat = CameraRenderer.getLocalSeesChat();
        Boolean tags = CameraRenderer.getLocalNameTags();
        Boolean gui  = CameraRenderer.getLocalGuiMode();
        Boolean pg   = CameraRenderer.getLocalShowPlayerGuis();
        Integer sfps = CameraRenderer.getLocalStreamFps();
        Integer vfps = CameraRenderer.getLocalVirtualFps();
        if (flip != null) props.setProperty("cameraFlipped", flip.toString());
        if (chat != null) props.setProperty("cameraSeesChat", chat.toString());
        if (tags != null) props.setProperty("cameraNameTags", tags.toString());
        if (gui  != null) props.setProperty("cameraGuiMode", gui.toString());
        if (pg   != null) props.setProperty("cameraShowPlayerGuis", pg.toString());
        if (sfps != null) props.setProperty("cameraStreamFps",  sfps.toString());
        if (vfps != null) props.setProperty("cameraVirtualFps", vfps.toString());
        props.setProperty("hideCameraModels", Boolean.toString(CameraRenderer.isHideCameraModels()));
        props.setProperty("hideCameraNameTagsForPlayers", Boolean.toString(CameraRenderer.isHideCameraNameTagsForPlayers()));
        try (OutputStream out = Files.newOutputStream(path)) {
            props.store(out, "cameramod client overrides");
        } catch (IOException e) {
            LOGGER.warn("Failed to save cameramod-client.properties", e);
        }
    }

    /**
     * In a one-player singleplayer world, mirror a client setting change onto
     * the matching server game rule so the two stay in sync. No-op without an
     * integrated server (multiplayer) or when more than one player is connected
     * (LAN) — one client must not silently rewrite shared rules for others.
     */
    public static void syncGameRuleBoolToServer(
            net.minecraft.world.GameRules.Key<net.minecraft.world.GameRules.BooleanRule> key, boolean value) {
        net.minecraft.server.MinecraftServer server = MinecraftClient.getInstance().getServer();
        if (server == null || server.getCurrentPlayerCount() > 1) return;
        server.execute(() -> {
            net.minecraft.world.GameRules.BooleanRule rule = server.getGameRules().get(key);
            if (rule.get() != value) rule.set(value, server);
        });
    }

    /** Integer-rule counterpart of {@link #syncGameRuleBoolToServer}. */
    public static void syncGameRuleIntToServer(
            net.minecraft.world.GameRules.Key<net.minecraft.world.GameRules.IntRule> key, int value) {
        net.minecraft.server.MinecraftServer server = MinecraftClient.getInstance().getServer();
        if (server == null || server.getCurrentPlayerCount() > 1) return;
        server.execute(() -> {
            net.minecraft.world.GameRules.IntRule rule = server.getGameRules().get(key);
            if (rule.get() != value) rule.set(value, server);
        });
    }

    private static String sanitizeKey(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Resolved camera save location for the current session:
     * - Singleplayer: file lives inside the world save folder so it travels with
     *   the world (rename/copy the world, the cameras come with it).
     * - Multiplayer: scoped per server address under the global config dir, so a
     *   proxy network's sub-servers (same address) share one file.
     */
    private static Path resolveSavePath(MinecraftClient mc) {
        if (mc.getServer() != null) {
            return mc.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                    .resolve("cameramod-cameras.dat");
        }
        net.minecraft.client.network.ServerInfo entry = mc.getCurrentServerEntry();
        if (entry != null) {
            Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("cameramod-cameras");
            try { Files.createDirectories(dir); } catch (IOException ignored) {}
            return dir.resolve("mp_" + sanitizeKey(entry.address) + ".dat");
        }
        return null;
    }

    static void saveAttachedCameras(Path path) {
        Properties props = new Properties();
        props.setProperty("count", String.valueOf(pendingCameraRestore.size()));
        for (int i = 0; i < pendingCameraRestore.size(); i++) {
            PendingCamera pc = pendingCameraRestore.get(i);
            props.setProperty(i + ".x",  String.valueOf(pc.x()));
            props.setProperty(i + ".y",  String.valueOf(pc.y()));
            props.setProperty(i + ".z",  String.valueOf(pc.z()));
            props.setProperty(i + ".yaw",   String.valueOf(pc.yaw()));
            props.setProperty(i + ".pitch", String.valueOf(pc.pitch()));
            props.setProperty(i + ".bound",   String.valueOf(pc.wasBound()));
            props.setProperty(i + ".stream",  String.valueOf(pc.streaming()));
            props.setProperty(i + ".attachMode",    String.valueOf(pc.attachMode()));
            props.setProperty(i + ".gravity",       String.valueOf(pc.gravityEnabled()));
            if (pc.fixedTargetUuid() != null) {
                props.setProperty(i + ".fixedTarget", pc.fixedTargetUuid().toString());
            }
            props.setProperty(i + ".fixerMode",     String.valueOf(pc.fixerMode()));
            if (pc.uuid() != null) props.setProperty(i + ".uuid", pc.uuid().toString());
            if (pc.name() != null) props.setProperty(i + ".name", pc.name());
        }
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException ignored) {}
        try (OutputStream out = Files.newOutputStream(path)) {
            props.store(out, "cameramod attached cameras — do not edit while game is running");
        } catch (IOException e) {
            LOGGER.warn("Failed to save {}", path, e);
        }
    }

    private static void loadAttachedCameras(Path path) {
        if (!Files.exists(path)) return;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            int count = Integer.parseInt(props.getProperty("count", "0"));
            pendingCameraRestore.clear();
            for (int i = 0; i < count; i++) {
                double x    = Double.parseDouble(props.getProperty(i + ".x", "0"));
                double y    = Double.parseDouble(props.getProperty(i + ".y", "0"));
                double z    = Double.parseDouble(props.getProperty(i + ".z", "0"));
                float yaw   = Float.parseFloat(props.getProperty(i + ".yaw",   "0"));
                float pitch = Float.parseFloat(props.getProperty(i + ".pitch", "0"));
                boolean bound   = Boolean.parseBoolean(props.getProperty(i + ".bound",   "false"));
                boolean stream  = Boolean.parseBoolean(props.getProperty(i + ".stream",  "false"));
                byte attachMode = Byte.parseByte(props.getProperty(i + ".attachMode", "0"));
                boolean gravity = Boolean.parseBoolean(props.getProperty(i + ".gravity", "false"));
                String fixedStr = props.getProperty(i + ".fixedTarget");
                java.util.UUID fixedTarget = null;
                if (fixedStr != null && !fixedStr.isEmpty()) {
                    try { fixedTarget = java.util.UUID.fromString(fixedStr); } catch (IllegalArgumentException ignored) {}
                }
                byte fixerMode = Byte.parseByte(props.getProperty(i + ".fixerMode", "0"));
                String uuidStr = props.getProperty(i + ".uuid", "");
                java.util.UUID uuid = null;
                if (!uuidStr.isEmpty()) {
                    try { uuid = java.util.UUID.fromString(uuidStr); } catch (IllegalArgumentException ignored) {}
                }
                String name = props.getProperty(i + ".name", "");
                if (name.isEmpty()) name = null;
                pendingCameraRestore.add(new PendingCamera(x, y, z, yaw, pitch, bound, stream, attachMode, gravity, fixedTarget, fixerMode, uuid, name));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load {}", path, e);
        }
    }
}
