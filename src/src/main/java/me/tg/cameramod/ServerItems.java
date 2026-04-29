package me.tg.cameramod;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.component.type.TooltipDisplayComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

import java.util.*;
import java.util.function.Consumer;

import static me.tg.cameramod.Cameramod.CAMERA_ENTITY_ENTITY_TYPE;

public class ServerItems {

    // ---- Item Registrations ----
    static RegistryKey<Item> camera_item_key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Cameramod.MOD_ID, "camera_item"));
    public static final Item CAMERA_ITEM = new CameraItem(new Item.Settings().maxCount(1).registryKey(camera_item_key));

    static RegistryKey<Item> camera_activator_key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Cameramod.MOD_ID, "camera_activator"));
    public static final Item CAMERA_ACTIVATOR = new CameraActivatorItem(new Item.Settings().maxCount(1).registryKey(camera_activator_key));

    static RegistryKey<Item> camera_orienter_key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Cameramod.MOD_ID, "camera_orienter"));
    public static final Item CAMERA_ORIENTER = new CameraOrienterItem(new Item.Settings().maxCount(1).registryKey(camera_orienter_key));

    static RegistryKey<Item> camera_mover_key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Cameramod.MOD_ID, "camera_mover"));
    public static final Item CAMERA_MOVER = new CameraMoverItem(new Item.Settings().maxCount(1).registryKey(camera_mover_key));

    static RegistryKey<Item> camera_fixer_key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Cameramod.MOD_ID, "camera_fixer"));
    public static final Item CAMERA_FIXER = new CameraFixerItem(new Item.Settings().maxCount(1).registryKey(camera_fixer_key));

    static RegistryKey<Item> camera_zoomer_key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Cameramod.MOD_ID, "camera_zoomer"));
    public static final Item CAMERA_ZOOMER = new CameraZoomerItem(new Item.Settings().maxCount(1).registryKey(camera_zoomer_key));

    static RegistryKey<Item> camera_gravity_key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Cameramod.MOD_ID, "camera_gravity"));
    public static final Item CAMERA_GRAVITY = new CameraGravityItem(new Item.Settings().maxCount(1).registryKey(camera_gravity_key));

    static RegistryKey<Item> camera_attacher_key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Cameramod.MOD_ID, "camera_attacher"));
    public static final Item CAMERA_ATTACHER = new CameraAttacherItem(new Item.Settings().maxCount(1).registryKey(camera_attacher_key));

    // ---- Storage Maps ----
    public static final HashMap<UUID, UUID> CAMERA_COMMAND_STORAGE = new HashMap<>();
    public static final HashMap<UUID, Double> CAMERA_MOVER_DISTANCE = new HashMap<>();
    public static final HashMap<UUID, Boolean> CAMERA_MOVER_ACTIVENESS = new HashMap<>();
    public static final HashMap<UUID, UUID> CAMERA_MOVER_UUIDS = new HashMap<>();
    public static final HashMap<UUID, UUID> CAMERA_FIXER_SELECTION = new HashMap<>();
    public static final HashMap<UUID, UUID> CAMERA_ZOOMER_UUIDS = new HashMap<>();

    // Saved camera UUID (persisted across restarts via Fabric attachment)
    public static final AttachmentType<String> SAVED_CAMERA_ATTACHMENT = AttachmentRegistry.createPersistent(
            Identifier.of(Cameramod.MOD_ID, "saved_camera"), Codec.STRING);

    // Streaming toggle: true = streaming on, false = off image
    public static final HashMap<UUID, Boolean> STREAMING_ENABLED = new HashMap<>();

    // Cached gamerule values for change detection
    private static boolean lastCameraSeesChat = false;
    private static boolean lastCameraFlipped = false;

    // Track force-loaded chunks per camera UUID so we can unload them when camera moves/dies
    private static final HashMap<UUID, Set<Long>> FORCED_CHUNKS = new HashMap<>();

    public static void registerItems() {
        Registry.register(Registries.ITEM, camera_item_key, CAMERA_ITEM);
        Registry.register(Registries.ITEM, camera_activator_key, CAMERA_ACTIVATOR);
        Registry.register(Registries.ITEM, camera_orienter_key, CAMERA_ORIENTER);
        Registry.register(Registries.ITEM, camera_mover_key, CAMERA_MOVER);
        Registry.register(Registries.ITEM, camera_fixer_key, CAMERA_FIXER);
        Registry.register(Registries.ITEM, camera_gravity_key, CAMERA_GRAVITY);
        Registry.register(Registries.ITEM, camera_attacher_key, CAMERA_ATTACHER);
        Registry.register(Registries.ITEM, camera_zoomer_key, CAMERA_ZOOMER);
        ServerTickEvents.START_SERVER_TICK.register(ServerItems::onServerTick);

        // On player join: sync gamerule values and auto-activate saved camera
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            // Sync gamerule values to client
            boolean seesChat = server.getGameRules().getBoolean(Cameramod.CAMERA_SEES_CHAT);
            boolean flipped = server.getGameRules().getBoolean(Cameramod.CAMERA_FLIPPED);
            ServerPlayNetworking.send(player, new CameraServerThing.CameraItemStateS2CPayload((byte) 3, seesChat));
            ServerPlayNetworking.send(player, new CameraServerThing.CameraItemStateS2CPayload((byte) 4, flipped));

            String savedStr = player.getAttached(SAVED_CAMERA_ATTACHMENT);
            if (savedStr != null) {
                try {
                    UUID camUuid = UUID.fromString(savedStr);
                    CAMERA_COMMAND_STORAGE.put(player.getUuid(), camUuid);
                    STREAMING_ENABLED.put(player.getUuid(), true);
                    ServerPlayNetworking.send(player,
                            new CameraServerThing.CameraItemStateS2CPayload((byte) 2, true));
                    ServerPlayNetworking.send(player,
                            new CameraServerThing.BindCameraS2CPayload(camUuid));
                } catch (IllegalArgumentException ignored) {}
            }
        });

        // On player disconnect: save active camera to attachment for next session
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID activeCamera = CAMERA_COMMAND_STORAGE.get(player.getUuid());
            if (activeCamera != null) {
                player.setAttached(SAVED_CAMERA_ATTACHMENT, activeCamera.toString());
            }
            // Clean up runtime maps
            CAMERA_COMMAND_STORAGE.remove(player.getUuid());
            STREAMING_ENABLED.remove(player.getUuid());
        });
    }

    // ==================== Raycast Helpers ====================

    public static CameraEntity raycastCamera(World world, PlayerEntity player, double maxDistance) {
        Entity hit = raycastEntity(world, player, maxDistance);
        return hit instanceof CameraEntity cam ? cam : null;
    }

    public static Entity raycastEntity(World world, PlayerEntity player, double maxDistance) {
        Vec3d start = player.getCameraPosVec(1.0f);
        Vec3d direction = player.getRotationVec(1.0f);
        Vec3d end = start.add(direction.multiply(maxDistance));
        Box searchBox = new Box(start, end).expand(1.0);

        Entity closest = null;
        double closestDist = Double.MAX_VALUE;

        for (Entity entity : world.getOtherEntities(player, searchBox)) {
            Box box = entity.getBoundingBox().expand(0.3);
            var hit = box.raycast(start, end);
            if (hit.isPresent()) {
                double dist = start.squaredDistanceTo(hit.get());
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = entity;
                }
            }
        }
        return closest;
    }

    private static boolean isCreative(PlayerEntity player) {
        if (player instanceof ServerPlayerEntity sp) {
            return sp.interactionManager.getGameMode() == GameMode.CREATIVE;
        }
        return player.isCreative();
    }

    // ==================== Scroll Handlers ====================

    public static void handleMoverScroll(ServerPlayerEntity player, float delta) {
        UUID userId = player.getUuid();
        if (!Boolean.TRUE.equals(CAMERA_MOVER_ACTIVENESS.get(userId))) return;
        Double dist = CAMERA_MOVER_DISTANCE.get(userId);
        if (dist == null) return;
        dist = Math.max(1.0, dist + delta * 0.5);
        CAMERA_MOVER_DISTANCE.put(userId, dist);
        player.sendMessage(Text.literal("Distance: " + String.format("%.1f", dist)), true);
    }

    public static void clearZoomerTarget(ServerPlayerEntity player) {
        UUID userId = player.getUuid();
        if (CAMERA_ZOOMER_UUIDS.containsKey(userId)) {
            CAMERA_ZOOMER_UUIDS.remove(userId);
            ServerPlayNetworking.send(player, new CameraServerThing.CameraItemStateS2CPayload((byte) 1, false));
            player.sendMessage(Text.literal("Zoom target cleared"), true);
        }
    }

    public static void handleZoomerScroll(ServerPlayerEntity player, float delta) {
        UUID userId = player.getUuid();
        UUID camUuid = CAMERA_ZOOMER_UUIDS.get(userId);
        if (camUuid == null) return;
        ServerWorld world = (ServerWorld) player.getWorld();
        Entity entity = world.getEntity(camUuid);
        if (!(entity instanceof CameraEntity cam)) return;

        float zoom = cam.getZoomLevel();
        // Below 1.0 (or scrolling DOWN from exactly 1.0) → fine 0.05 steps.
        // At/above 1.0 scrolling up → coarse 0.25 steps. Min 0.5 (0.25 caused
        // a flipped image).
        boolean fine = zoom < 1.0f || (zoom == 1.0f && delta < 0);
        float step = fine ? 0.05f : 0.25f;
        zoom += delta * step;
        zoom = Math.max(0.5f, Math.min(10.0f, zoom));
        cam.setZoomLevel(zoom);
        player.sendMessage(Text.literal("Zoom: " + String.format("%.2f", zoom) + "x"), true);
    }

    // ==================== Chunk Force-Loading ====================

    private static void updateForcedChunks(ServerWorld world, CameraEntity cam) {
        UUID camId = cam.getUuid();
        ChunkPos center = cam.getChunkPos();
        int radius = 3; // 7x7 chunk area

        Set<Long> newChunks = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                newChunks.add(ChunkPos.toLong(center.x + dx, center.z + dz));
            }
        }

        Set<Long> oldChunks = FORCED_CHUNKS.getOrDefault(camId, Collections.emptySet());

        // Un-force old chunks no longer needed
        for (long pos : oldChunks) {
            if (!newChunks.contains(pos)) {
                world.setChunkForced(ChunkPos.getPackedX(pos), ChunkPos.getPackedZ(pos), false);
            }
        }

        // Force new chunks
        for (long pos : newChunks) {
            if (!oldChunks.contains(pos)) {
                world.setChunkForced(ChunkPos.getPackedX(pos), ChunkPos.getPackedZ(pos), true);
            }
        }

        FORCED_CHUNKS.put(camId, newChunks);
    }

    /** Called when a camera is unbound or removed — cleans up forced chunks. */
    public static void clearForcedChunks(ServerWorld world, UUID camId) {
        Set<Long> chunks = FORCED_CHUNKS.remove(camId);
        if (chunks != null) {
            for (long pos : chunks) {
                world.setChunkForced(ChunkPos.getPackedX(pos), ChunkPos.getPackedZ(pos), false);
            }
        }
    }

    // ==================== Camera Item ====================
    public static class CameraItem extends Item {
        public CameraItem(Settings settings) { super(settings); }

        @Override
        public void appendTooltip(ItemStack stack, TooltipContext context, TooltipDisplayComponent displayComponent,
                                  Consumer<Text> textConsumer, TooltipType type) {
            textConsumer.accept(Text.translatable("item.cameramod.camera_item.tooltip.1").formatted(Formatting.GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_item.tooltip.2").formatted(Formatting.DARK_GRAY));
        }

        @Override
        public ActionResult useOnBlock(ItemUsageContext context) {
            World world = context.getWorld();
            if (world.isClient) return ActionResult.SUCCESS;

            ItemStack itemStack = context.getStack();
            BlockPos blockPos = context.getBlockPos();
            Direction direction = context.getSide();
            BlockState blockState = world.getBlockState(blockPos);

            BlockPos spawnPos;
            if (blockState.getCollisionShape(world, blockPos).isEmpty()) {
                spawnPos = blockPos;
            } else {
                spawnPos = blockPos.offset(direction);
            }

            if (((EntityType<?>) CAMERA_ENTITY_ENTITY_TYPE).spawnFromItemStack(
                    (ServerWorld) world, itemStack, context.getPlayer(), spawnPos,
                    SpawnReason.SPAWN_ITEM_USE, true,
                    !Objects.equals(blockPos, spawnPos) && direction == Direction.UP) != null) {
                itemStack.decrement(1);
                world.emitGameEvent(context.getPlayer(), GameEvent.ENTITY_PLACE, blockPos);
                if (context.getPlayer() != null) {
                    context.getPlayer().sendMessage(Text.literal("Camera placed"), true);
                }
            }

            return ActionResult.SUCCESS;
        }

        @Override
        public ActionResult use(World world, PlayerEntity user, Hand hand) {
            ItemStack itemStack = user.getStackInHand(hand);
            BlockHitResult blockHitResult = raycast(world, user, RaycastContext.FluidHandling.SOURCE_ONLY);
            if (blockHitResult.getType() != HitResult.Type.BLOCK) return ActionResult.PASS;
            if (!(world instanceof ServerWorld serverWorld)) return ActionResult.SUCCESS;

            BlockPos blockPos = blockHitResult.getBlockPos();
            if (!(world.getBlockState(blockPos).getBlock() instanceof FluidBlock)) return ActionResult.PASS;
            if (!world.canEntityModifyAt(user, blockPos) || !user.canPlaceOn(blockPos, blockHitResult.getSide(), itemStack))
                return ActionResult.FAIL;

            Entity entity = ((EntityType<?>) CAMERA_ENTITY_ENTITY_TYPE).spawnFromItemStack(
                    serverWorld, itemStack, user, blockPos, SpawnReason.SPAWN_ITEM_USE, false, false);
            if (entity == null) return ActionResult.PASS;

            itemStack.decrementUnlessCreative(1, user);
            user.incrementStat(Stats.USED.getOrCreateStat(this));
            world.emitGameEvent(user, GameEvent.ENTITY_PLACE, entity.getPos());
            user.sendMessage(Text.literal("Camera placed"), true);
            return ActionResult.SUCCESS;
        }
    }

    // ==================== Camera Activator ====================
    public static class CameraActivatorItem extends Item {
        public CameraActivatorItem(Settings settings) { super(settings); }

        @Override
        public void appendTooltip(ItemStack stack, TooltipContext context, TooltipDisplayComponent displayComponent,
                                  Consumer<Text> textConsumer, TooltipType type) {
            textConsumer.accept(Text.translatable("item.cameramod.camera_activator.tooltip.1").formatted(Formatting.GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_activator.tooltip.2").formatted(Formatting.DARK_GRAY));
        }

        @Override
        public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
            if (user.getWorld().isClient) return ActionResult.SUCCESS;
            if (entity instanceof CameraEntity) {
                return toggleBind(user, entity);
            }
            user.sendMessage(Text.literal("Not a camera entity"), true);
            return ActionResult.PASS;
        }

        @Override
        public ActionResult use(World world, PlayerEntity user, Hand hand) {
            if (world.isClient) return ActionResult.SUCCESS;

            // Creative: 20-block raycast for distant cameras
            if (isCreative(user)) {
                CameraEntity cam = raycastCamera(world, user, 20.0);
                if (cam != null) return toggleBind(user, cam);
            }

            UUID userId = user.getUuid();
            boolean isStreamingOn = Boolean.TRUE.equals(STREAMING_ENABLED.get(userId));

            if (isStreamingOn) {
                // Streaming is ON → toggle OFF
                // If a camera is active, save it and unbind
                UUID activeCamera = CAMERA_COMMAND_STORAGE.get(userId);
                if (activeCamera != null) {
                    CAMERA_COMMAND_STORAGE.remove(userId);
                    if (user instanceof ServerPlayerEntity sp) {
                        clearForcedChunks((ServerWorld) user.getWorld(), activeCamera);
                        ServerPlayNetworking.send(sp, new CameraServerThing.UnbindCameraS2CPayload());
                        sp.setAttached(SAVED_CAMERA_ATTACHMENT, activeCamera.toString());
                    }
                }
                STREAMING_ENABLED.put(userId, false);
                if (user instanceof ServerPlayerEntity sp) {
                    ServerPlayNetworking.send(sp, new CameraServerThing.CameraItemStateS2CPayload((byte) 2, false));
                }
                user.sendMessage(Text.literal("Streaming disabled"), true);
            } else {
                // Streaming is OFF → toggle ON
                STREAMING_ENABLED.put(userId, true);
                if (user instanceof ServerPlayerEntity sp) {
                    ServerPlayNetworking.send(sp, new CameraServerThing.CameraItemStateS2CPayload((byte) 2, true));
                }

                // Try to re-activate saved camera
                String savedStr = (user instanceof ServerPlayerEntity sp)
                        ? sp.getAttached(SAVED_CAMERA_ATTACHMENT) : null;
                if (savedStr != null) {
                    try {
                        UUID savedCamera = UUID.fromString(savedStr);
                        CAMERA_COMMAND_STORAGE.put(userId, savedCamera);
                        if (user instanceof ServerPlayerEntity sp2) {
                            ServerPlayNetworking.send(sp2,
                                    new CameraServerThing.BindCameraS2CPayload(savedCamera));
                        }
                        user.sendMessage(Text.literal("Streaming enabled (camera re-activated)"), true);
                    } catch (IllegalArgumentException e) {
                        user.sendMessage(Text.literal("Streaming enabled (player POV)"), true);
                    }
                } else {
                    // No saved camera → stream player POV
                    user.sendMessage(Text.literal("Streaming enabled (player POV)"), true);
                }
            }
            return ActionResult.SUCCESS;
        }

        private ActionResult toggleBind(PlayerEntity user, Entity entity) {
            UUID userId = user.getUuid();
            UUID currentBound = CAMERA_COMMAND_STORAGE.get(userId);
            if (entity.getUuid().equals(currentBound)) {
                // Already bound to this camera — unbind, unsave, and disable streaming
                CAMERA_COMMAND_STORAGE.remove(userId);
                STREAMING_ENABLED.put(userId, false);
                if (user instanceof ServerPlayerEntity sp) {
                    clearForcedChunks((ServerWorld) user.getWorld(), entity.getUuid());
                    ServerPlayNetworking.send(sp, new CameraServerThing.CameraItemStateS2CPayload((byte) 2, false));
                    ServerPlayNetworking.send(sp, new CameraServerThing.UnbindCameraS2CPayload());
                    sp.removeAttached(SAVED_CAMERA_ATTACHMENT);
                }
                user.sendMessage(Text.literal("Camera unbound"), true);
            } else {
                // Bind to this camera (also saves it and enables streaming)
                CAMERA_COMMAND_STORAGE.put(userId, entity.getUuid());
                STREAMING_ENABLED.put(userId, true);
                if (user instanceof ServerPlayerEntity sp) {
                    ServerPlayNetworking.send(sp, new CameraServerThing.CameraItemStateS2CPayload((byte) 2, true));
                    ServerPlayNetworking.send(sp,
                            new CameraServerThing.BindCameraS2CPayload(entity.getUuid()));
                    sp.setAttached(SAVED_CAMERA_ATTACHMENT, entity.getUuid().toString());
                }
                user.sendMessage(Text.literal("Camera bound to you"), true);
            }
            return ActionResult.SUCCESS;
        }
    }

    // ==================== Camera Orienter ====================
    public static class CameraOrienterItem extends Item {
        public CameraOrienterItem(Settings settings) { super(settings); }

        @Override
        public void appendTooltip(ItemStack stack, TooltipContext context, TooltipDisplayComponent displayComponent,
                                  Consumer<Text> textConsumer, TooltipType type) {
            textConsumer.accept(Text.translatable("item.cameramod.camera_orienter.tooltip.1").formatted(Formatting.GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_orienter.tooltip.2").formatted(Formatting.DARK_GRAY));
        }

        @Override
        public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
            if (user.getWorld().isClient) return ActionResult.SUCCESS;
            if (entity instanceof CameraEntity cam) {
                orientCamera(cam, user);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        }

        @Override
        public ActionResult use(World world, PlayerEntity player, Hand hand) {
            if (world.isClient || player == null) return ActionResult.SUCCESS;

            // Creative: 20-block raycast for distant cameras
            if (isCreative(player)) {
                CameraEntity cam = raycastCamera(world, player, 20.0);
                if (cam != null) {
                    orientCamera(cam, player);
                    return ActionResult.SUCCESS;
                }
            }

            // Click air: orient bound camera
            UUID camUuid = CAMERA_COMMAND_STORAGE.get(player.getUuid());
            if (camUuid == null) {
                player.sendMessage(Text.literal("No camera bound. Use Camera Activator first."), true);
                return ActionResult.PASS;
            }
            Entity cam = ((ServerWorld) world).getEntity(camUuid);
            if (cam == null) {
                player.sendMessage(Text.literal("Bound camera not found"), true);
                return ActionResult.PASS;
            }
            if (cam instanceof CameraEntity ce) orientCamera(ce, player);
            return ActionResult.SUCCESS;
        }

        private void orientCamera(CameraEntity cam, PlayerEntity player) {
            cam.setYaw(player.getYaw());
            cam.setPitch(player.getPitch());
            cam.setHeadYaw(player.getYaw());
            cam.setBodyYaw(player.getYaw());
            player.sendMessage(Text.literal("Camera oriented"), true);
        }
    }

    // ==================== Camera Mover ====================
    public static class CameraMoverItem extends Item {
        public CameraMoverItem(Settings settings) { super(settings); }

        @Override
        public void appendTooltip(ItemStack stack, TooltipContext context, TooltipDisplayComponent displayComponent,
                                  Consumer<Text> textConsumer, TooltipType type) {
            textConsumer.accept(Text.translatable("item.cameramod.camera_mover.tooltip.1").formatted(Formatting.GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_mover.tooltip.2").formatted(Formatting.DARK_GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_mover.tooltip.3").formatted(Formatting.DARK_GRAY));
        }

        @Override
        public ActionResult use(World world, PlayerEntity user, Hand hand) {
            if (world.isClient) return ActionResult.SUCCESS;

            UUID userId = user.getUuid();

            if (Boolean.TRUE.equals(CAMERA_MOVER_ACTIVENESS.get(userId))) {
                stopMover(user);
                return ActionResult.SUCCESS;
            }

            // 20-block raycast to start following a distant camera
            CameraEntity cam = raycastCamera(world, user, 20.0);
            if (cam != null) {
                startMover(user, cam);
                return ActionResult.SUCCESS;
            }

            return ActionResult.PASS;
        }

        @Override
        public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
            if (user.getWorld().isClient) return ActionResult.SUCCESS;
            if (!(entity instanceof CameraEntity cam)) return ActionResult.PASS;

            if (!Boolean.TRUE.equals(CAMERA_MOVER_ACTIVENESS.get(user.getUuid()))) {
                startMover(user, cam);
            } else {
                stopMover(user);
            }
            return ActionResult.SUCCESS;
        }

        private void startMover(PlayerEntity user, CameraEntity cam) {
            UUID userId = user.getUuid();
            double dist = cam.getPos().distanceTo(user.getPos());
            CAMERA_MOVER_DISTANCE.put(userId, dist);
            CAMERA_MOVER_UUIDS.put(userId, cam.getUuid());
            CAMERA_MOVER_ACTIVENESS.put(userId, true);
            cam.setBeingMoved(true);
            if (user instanceof ServerPlayerEntity sp) {
                ServerPlayNetworking.send(sp, new CameraServerThing.CameraItemStateS2CPayload((byte) 0, true));
            }
            user.sendMessage(Text.literal("Camera following at distance " + String.format("%.1f", dist)), true);
        }

        private void stopMover(PlayerEntity user) {
            UUID userId = user.getUuid();
            UUID camUuid = CAMERA_MOVER_UUIDS.get(userId);
            if (camUuid != null && user.getWorld() instanceof ServerWorld sw) {
                Entity e = sw.getEntity(camUuid);
                if (e instanceof CameraEntity ce) ce.setBeingMoved(false);
            }
            CAMERA_MOVER_DISTANCE.remove(userId);
            CAMERA_MOVER_ACTIVENESS.remove(userId);
            CAMERA_MOVER_UUIDS.remove(userId);
            if (user instanceof ServerPlayerEntity sp) {
                ServerPlayNetworking.send(sp, new CameraServerThing.CameraItemStateS2CPayload((byte) 0, false));
            }
            user.sendMessage(Text.literal("Camera tracking stopped"), true);
        }
    }

    // ==================== Camera Fixer ====================
    public static class CameraFixerItem extends Item {
        public CameraFixerItem(Settings settings) { super(settings); }

        @Override
        public void appendTooltip(ItemStack stack, TooltipContext context, TooltipDisplayComponent displayComponent,
                                  Consumer<Text> textConsumer, TooltipType type) {
            textConsumer.accept(Text.translatable("item.cameramod.camera_fixer.tooltip.1").formatted(Formatting.GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_fixer.tooltip.2").formatted(Formatting.DARK_GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_fixer.tooltip.3").formatted(Formatting.DARK_GRAY));
        }

        @Override
        public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
            if (user.getWorld().isClient) return ActionResult.SUCCESS;

            UUID userId = user.getUuid();

            if (entity instanceof CameraEntity cam) {
                if (user.isSneaking()) {
                    CAMERA_FIXER_SELECTION.put(userId, cam.getUuid());
                    user.sendMessage(Text.literal("Camera selected. Now click an entity to fix the camera to it."), true);
                } else {
                    if (cam.getFixedTargetUuid() != null && cam.getFixedTargetUuid().equals(user.getUuid())) {
                        cam.setFixedTargetUuid(null);
                        user.sendMessage(Text.literal("Camera tracking disabled"), true);
                    } else {
                        cam.setFixedTargetUuid(user.getUuid());
                        user.sendMessage(Text.literal("Camera now tracks you"), true);
                    }
                }
                return ActionResult.SUCCESS;
            }

            UUID selectedCamUuid = CAMERA_FIXER_SELECTION.get(userId);
            if (selectedCamUuid != null) {
                ServerWorld world = (ServerWorld) user.getWorld();
                Entity camEntity = world.getEntity(selectedCamUuid);
                if (camEntity instanceof CameraEntity cam) {
                    cam.setFixedTargetUuid(entity.getUuid());
                    CAMERA_FIXER_SELECTION.remove(userId);
                    user.sendMessage(Text.literal("Camera now tracks " + entity.getName().getString()), true);
                    return ActionResult.SUCCESS;
                } else {
                    CAMERA_FIXER_SELECTION.remove(userId);
                    user.sendMessage(Text.literal("Selected camera no longer exists"), true);
                }
            }

            return ActionResult.PASS;
        }

        @Override
        public ActionResult use(World world, PlayerEntity user, Hand hand) {
            if (world.isClient) return ActionResult.SUCCESS;

            UUID userId = user.getUuid();

            // 20-block raycast for camera or entity
            Entity hit = raycastEntity(world, user, 20.0);

            // If we have a selection pending and hit a non-camera entity, assign target
            UUID selectedCamUuid = CAMERA_FIXER_SELECTION.get(userId);
            if (selectedCamUuid != null) {
                if (hit != null && !(hit instanceof CameraEntity)) {
                    ServerWorld sw = (ServerWorld) world;
                    Entity camEntity = sw.getEntity(selectedCamUuid);
                    if (camEntity instanceof CameraEntity cam) {
                        cam.setFixedTargetUuid(hit.getUuid());
                        CAMERA_FIXER_SELECTION.remove(userId);
                        user.sendMessage(Text.literal("Camera now tracks " + hit.getName().getString()), true);
                        return ActionResult.SUCCESS;
                    }
                }
                CAMERA_FIXER_SELECTION.remove(userId);
                user.sendMessage(Text.literal("Camera selection cleared"), true);
                return ActionResult.SUCCESS;
            }

            // 20-block reach: interact with distant camera
            if (hit instanceof CameraEntity cam) {
                if (user.isSneaking()) {
                    CAMERA_FIXER_SELECTION.put(userId, cam.getUuid());
                    user.sendMessage(Text.literal("Camera selected. Now click an entity to fix the camera to it."), true);
                } else {
                    if (cam.getFixedTargetUuid() != null && cam.getFixedTargetUuid().equals(user.getUuid())) {
                        cam.setFixedTargetUuid(null);
                        user.sendMessage(Text.literal("Camera tracking disabled"), true);
                    } else {
                        cam.setFixedTargetUuid(user.getUuid());
                        user.sendMessage(Text.literal("Camera now tracks you"), true);
                    }
                }
                return ActionResult.SUCCESS;
            }

            // Click air: toggle fixer mode on bound camera
            UUID camUuid = CAMERA_COMMAND_STORAGE.get(userId);
            if (camUuid != null) {
                ServerWorld sw = (ServerWorld) world;
                Entity camEntity = sw.getEntity(camUuid);
                if (camEntity instanceof CameraEntity cam) {
                    byte mode = cam.getFixerMode();
                    byte newMode = (byte) ((mode + 1) % 2);
                    cam.setFixerMode(newMode);
                    String modeName = newMode == 0 ? "Look At" : "Look Same Way";
                    user.sendMessage(Text.literal("Fixer mode: " + modeName), true);
                    return ActionResult.SUCCESS;
                }
            }

            user.sendMessage(Text.literal("Shift+click a camera to select, then click an entity to fix"), true);
            return ActionResult.SUCCESS;
        }
    }

    // ==================== Camera Zoomer ====================
    public static class CameraZoomerItem extends Item {
        public CameraZoomerItem(Settings settings) { super(settings); }

        @Override
        public void appendTooltip(ItemStack stack, TooltipContext context, TooltipDisplayComponent displayComponent,
                                  Consumer<Text> textConsumer, TooltipType type) {
            textConsumer.accept(Text.translatable("item.cameramod.camera_zoomer.tooltip.1").formatted(Formatting.GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_zoomer.tooltip.2").formatted(Formatting.DARK_GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_zoomer.tooltip.3").formatted(Formatting.DARK_GRAY));
        }

        @Override
        public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
            if (user.getWorld().isClient) return ActionResult.SUCCESS;
            if (!(entity instanceof CameraEntity cam)) return ActionResult.PASS;
            if (!user.isSneaking()) return ActionResult.PASS;
            return setZoomTarget(user, cam);
        }

        @Override
        public ActionResult use(World world, PlayerEntity user, Hand hand) {
            if (world.isClient) return ActionResult.SUCCESS;

            UUID userId = user.getUuid();

            // Shift+click: try to target a camera (20-block raycast)
            if (user.isSneaking()) {
                CameraEntity cam = raycastCamera(world, user, 20.0);
                if (cam != null) return setZoomTarget(user, cam);
            }

            // Click air: clear zoom target
            if (CAMERA_ZOOMER_UUIDS.containsKey(userId)) {
                CAMERA_ZOOMER_UUIDS.remove(userId);
                if (user instanceof ServerPlayerEntity sp) {
                    ServerPlayNetworking.send(sp, new CameraServerThing.CameraItemStateS2CPayload((byte) 1, false));
                }
                user.sendMessage(Text.literal("Zoom target cleared"), true);
                return ActionResult.SUCCESS;
            }

            user.sendMessage(Text.literal("Shift+click a camera to set zoom target"), true);
            return ActionResult.SUCCESS;
        }

        private ActionResult setZoomTarget(PlayerEntity user, CameraEntity cam) {
            CAMERA_ZOOMER_UUIDS.put(user.getUuid(), cam.getUuid());
            if (user instanceof ServerPlayerEntity sp) {
                ServerPlayNetworking.send(sp, new CameraServerThing.CameraItemStateS2CPayload((byte) 1, true));
            }
            user.sendMessage(Text.literal("Zoom target set (" + String.format("%.2f", cam.getZoomLevel()) + "x). Hold shift + scroll to adjust."), true);
            return ActionResult.SUCCESS;
        }
    }

    // ==================== Camera Gravity ====================
    public static class CameraGravityItem extends Item {
        public CameraGravityItem(Settings settings) { super(settings); }

        @Override
        public void appendTooltip(ItemStack stack, TooltipContext context, TooltipDisplayComponent displayComponent,
                                  Consumer<Text> textConsumer, TooltipType type) {
            textConsumer.accept(Text.translatable("item.cameramod.camera_gravity.tooltip.1").formatted(Formatting.GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_gravity.tooltip.2").formatted(Formatting.DARK_GRAY));
        }

        @Override
        public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
            if (user.getWorld().isClient) return ActionResult.SUCCESS;
            if (entity instanceof CameraEntity cam) {
                toggleGravity(cam, user);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        }

        @Override
        public ActionResult use(World world, PlayerEntity user, Hand hand) {
            if (world.isClient) return ActionResult.SUCCESS;
            if (isCreative(user)) {
                CameraEntity cam = raycastCamera(world, user, 20.0);
                if (cam != null) {
                    toggleGravity(cam, user);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        }

        private void toggleGravity(CameraEntity cam, PlayerEntity user) {
            boolean newState = !cam.isGravityEnabled();
            cam.setGravityEnabled(newState);
            user.sendMessage(Text.literal("Camera gravity: " + (newState ? "ON" : "OFF")), true);
        }
    }

    // ==================== Camera Attacher ====================
    public static class CameraAttacherItem extends Item {
        public CameraAttacherItem(Settings settings) { super(settings); }

        @Override
        public void appendTooltip(ItemStack stack, TooltipContext context, TooltipDisplayComponent displayComponent,
                                  Consumer<Text> textConsumer, TooltipType type) {
            textConsumer.accept(Text.translatable("item.cameramod.camera_attacher.tooltip.1").formatted(Formatting.GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_attacher.tooltip.2").formatted(Formatting.DARK_GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_attacher.tooltip.3").formatted(Formatting.DARK_GRAY));
        }

        @Override
        public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
            if (user.getWorld().isClient) return ActionResult.SUCCESS;
            UUID userId = user.getUuid();

            if (entity instanceof CameraEntity cam) {
                if (user.isSneaking()) {
                    // Shift+click camera: select for attaching to another entity
                    CAMERA_FIXER_SELECTION.put(userId, cam.getUuid());
                    user.sendMessage(Text.literal("Camera selected. Now click an entity to attach to."), true);
                } else {
                    // Normal click camera: toggle attach to clicking player
                    if (cam.getAttachTargetUuid() != null && cam.getAttachTargetUuid().equals(user.getUuid())) {
                        cam.setAttachTargetUuid(null);
                        user.sendMessage(Text.literal("Camera detached"), true);
                    } else {
                        cam.setAttachTargetUuid(user.getUuid());
                        cam.setAttachOffset(cam.getPos().subtract(user.getPos()));
                        user.sendMessage(Text.literal("Camera now follows you"), true);
                    }
                }
                return ActionResult.SUCCESS;
            }

            // Click non-camera entity: attach selected camera to it
            UUID selectedCamUuid = CAMERA_FIXER_SELECTION.get(userId);
            if (selectedCamUuid != null) {
                ServerWorld world = (ServerWorld) user.getWorld();
                Entity camEntity = world.getEntity(selectedCamUuid);
                if (camEntity instanceof CameraEntity cam) {
                    cam.setAttachTargetUuid(entity.getUuid());
                    cam.setAttachOffset(cam.getPos().subtract(entity.getPos()));
                    CAMERA_FIXER_SELECTION.remove(userId);
                    user.sendMessage(Text.literal("Camera attached to " + entity.getName().getString()), true);
                    return ActionResult.SUCCESS;
                }
                CAMERA_FIXER_SELECTION.remove(userId);
                user.sendMessage(Text.literal("Selected camera no longer exists"), true);
            }

            return ActionResult.PASS;
        }

        @Override
        public ActionResult use(World world, PlayerEntity user, Hand hand) {
            if (world.isClient) return ActionResult.SUCCESS;
            UUID userId = user.getUuid();

            Entity hit = raycastEntity(world, user, 20.0);

            // Pending selection + non-camera entity hit: attach
            UUID selectedCamUuid = CAMERA_FIXER_SELECTION.get(userId);
            if (selectedCamUuid != null) {
                if (hit != null && !(hit instanceof CameraEntity)) {
                    ServerWorld sw = (ServerWorld) world;
                    Entity camEntity = sw.getEntity(selectedCamUuid);
                    if (camEntity instanceof CameraEntity cam) {
                        cam.setAttachTargetUuid(hit.getUuid());
                        cam.setAttachOffset(cam.getPos().subtract(hit.getPos()));
                        CAMERA_FIXER_SELECTION.remove(userId);
                        user.sendMessage(Text.literal("Camera attached to " + hit.getName().getString()), true);
                        return ActionResult.SUCCESS;
                    }
                }
                CAMERA_FIXER_SELECTION.remove(userId);
                user.sendMessage(Text.literal("Selection cleared"), true);
                return ActionResult.SUCCESS;
            }

            // Raycast camera: shift+click = select, normal click = toggle attach to self
            if (hit instanceof CameraEntity cam) {
                if (user.isSneaking()) {
                    CAMERA_FIXER_SELECTION.put(userId, cam.getUuid());
                    user.sendMessage(Text.literal("Camera selected. Now click an entity to attach to."), true);
                } else {
                    if (cam.getAttachTargetUuid() != null && cam.getAttachTargetUuid().equals(user.getUuid())) {
                        cam.setAttachTargetUuid(null);
                        user.sendMessage(Text.literal("Camera detached"), true);
                    } else {
                        cam.setAttachTargetUuid(user.getUuid());
                        cam.setAttachOffset(cam.getPos().subtract(user.getPos()));
                        user.sendMessage(Text.literal("Camera now follows you"), true);
                    }
                }
                return ActionResult.SUCCESS;
            }

            // Shift+click air: toggle attach mode on bound camera
            if (user.isSneaking()) {
                UUID camUuid = CAMERA_COMMAND_STORAGE.get(userId);
                if (camUuid != null) {
                    ServerWorld sw = (ServerWorld) world;
                    Entity camEntity = sw.getEntity(camUuid);
                    if (camEntity instanceof CameraEntity cam && cam.getAttachTargetUuid() != null) {
                        Entity target = sw.getEntity(cam.getAttachTargetUuid());
                        if (target != null) {
                            byte newMode = (byte) ((cam.getAttachMode() + 1) % 2);
                            Vec3d offset = cam.getAttachOffset();
                            float yawRad = (float) (target.getYaw() * Math.PI / 180.0);
                            if (newMode == 1) {
                                // World-space → local-space (un-rotate by entity yaw)
                                double localX =  offset.x * Math.cos(yawRad) + offset.z * Math.sin(yawRad);
                                double localZ = -offset.x * Math.sin(yawRad) + offset.z * Math.cos(yawRad);
                                cam.setAttachOffset(new Vec3d(localX, offset.y, localZ));
                            } else {
                                // Local-space → world-space (rotate by entity yaw)
                                double worldX = offset.x * Math.cos(yawRad) - offset.z * Math.sin(yawRad);
                                double worldZ = offset.x * Math.sin(yawRad) + offset.z * Math.cos(yawRad);
                                cam.setAttachOffset(new Vec3d(worldX, offset.y, worldZ));
                            }
                            cam.setAttachMode(newMode);
                            String modeName = newMode == 0 ? "Relative (world-space)" : "Head (rotates with entity)";
                            user.sendMessage(Text.literal("Attach mode: " + modeName), true);
                            return ActionResult.SUCCESS;
                        }
                    }
                }
            }

            return ActionResult.PASS;
        }
    }

    // ==================== Server Tick Handler ====================
    private static void onServerTick(MinecraftServer server) {
        // Sync gamerule changes to all players
        boolean seesChat = server.getGameRules().getBoolean(Cameramod.CAMERA_SEES_CHAT);
        boolean flipped = server.getGameRules().getBoolean(Cameramod.CAMERA_FLIPPED);
        if (seesChat != lastCameraSeesChat || flipped != lastCameraFlipped) {
            lastCameraSeesChat = seesChat;
            lastCameraFlipped = flipped;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ServerPlayNetworking.send(player, new CameraServerThing.CameraItemStateS2CPayload((byte) 3, seesChat));
                ServerPlayNetworking.send(player, new CameraServerThing.CameraItemStateS2CPayload((byte) 4, flipped));
            }
        }

        // Camera Mover: move cameras to follow players
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID userId = player.getUuid();
            if (!Boolean.TRUE.equals(CAMERA_MOVER_ACTIVENESS.get(userId))) continue;

            Double distance = CAMERA_MOVER_DISTANCE.get(userId);
            UUID camUuid = CAMERA_MOVER_UUIDS.get(userId);
            if (distance == null || camUuid == null) continue;

            ServerWorld world = (ServerWorld) player.getWorld();
            Entity camera = world.getEntity(camUuid);
            if (camera == null) {
                CAMERA_MOVER_ACTIVENESS.remove(userId);
                CAMERA_MOVER_DISTANCE.remove(userId);
                CAMERA_MOVER_UUIDS.remove(userId);
                continue;
            }

            Vec3d dir = player.getRotationVector().normalize();
            Vec3d target = player.getPos().add(dir.multiply(distance));
            camera.requestTeleport(target.x, target.y, target.z);
        }

        // Camera Fixer rotation is handled client-side for smoothness
        // (see CameramodClient WorldRenderEvents.START handler)

        // Force-load chunks around bound cameras
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID camUuid = CAMERA_COMMAND_STORAGE.get(player.getUuid());
            if (camUuid == null) continue;

            ServerWorld world = (ServerWorld) player.getWorld();
            Entity entity = world.getEntity(camUuid);
            if (entity instanceof CameraEntity cam) {
                updateForcedChunks(world, cam);
            }
        }
    }
}
