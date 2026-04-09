package me.tg.cameramod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
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

    // ---- Storage Maps ----
    public static final HashMap<UUID, UUID> CAMERA_COMMAND_STORAGE = new HashMap<>();
    public static final HashMap<UUID, Double> CAMERA_MOVER_DISTANCE = new HashMap<>();
    public static final HashMap<UUID, Boolean> CAMERA_MOVER_ACTIVENESS = new HashMap<>();
    public static final HashMap<UUID, UUID> CAMERA_MOVER_UUIDS = new HashMap<>();

    // Fixer: player UUID → selected camera UUID (for the two-step shift+click workflow)
    public static final HashMap<UUID, UUID> CAMERA_FIXER_SELECTION = new HashMap<>();

    public static void registerItems() {
        Registry.register(Registries.ITEM, camera_item_key, CAMERA_ITEM);
        Registry.register(Registries.ITEM, camera_activator_key, CAMERA_ACTIVATOR);
        Registry.register(Registries.ITEM, camera_orienter_key, CAMERA_ORIENTER);
        Registry.register(Registries.ITEM, camera_mover_key, CAMERA_MOVER);
        Registry.register(Registries.ITEM, camera_fixer_key, CAMERA_FIXER);
        ServerTickEvents.START_SERVER_TICK.register(ServerItems::onServerTick);
    }

    // ==================== Camera Item ====================
    // Spawns a camera entity in the world
    public static class CameraItem extends Item {
        public CameraItem(Settings settings) {
            super(settings);
        }

        @Override
        public void appendTooltip(ItemStack stack,
                                  TooltipContext context,
                                  TooltipDisplayComponent displayComponent,
                                  Consumer<Text> textConsumer,
                                  TooltipType type) {

            textConsumer.accept(Text.translatable("item.cameramod.camera_item.tooltip.1")
                    .formatted(Formatting.GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_item.tooltip.2")
                    .formatted(Formatting.DARK_GRAY));
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
            if (blockHitResult.getType() != HitResult.Type.BLOCK) {
                return ActionResult.PASS;
            }
            if (!(world instanceof ServerWorld serverWorld)) {
                return ActionResult.SUCCESS;
            }

            BlockPos blockPos = blockHitResult.getBlockPos();
            if (!(world.getBlockState(blockPos).getBlock() instanceof FluidBlock)) {
                return ActionResult.PASS;
            }
            if (!world.canEntityModifyAt(user, blockPos) || !user.canPlaceOn(blockPos, blockHitResult.getSide(), itemStack)) {
                return ActionResult.FAIL;
            }

            Entity entity = ((EntityType<?>) CAMERA_ENTITY_ENTITY_TYPE).spawnFromItemStack(
                    serverWorld, itemStack, user, blockPos, SpawnReason.SPAWN_ITEM_USE, false, false);
            if (entity == null) {
                return ActionResult.PASS;
            }

            itemStack.decrementUnlessCreative(1, user);
            user.incrementStat(Stats.USED.getOrCreateStat(this));
            world.emitGameEvent(user, GameEvent.ENTITY_PLACE, entity.getPos());
            user.sendMessage(Text.literal("Camera placed"), true);
            return ActionResult.SUCCESS;
        }
    }

    // ==================== Camera Activator ====================
    public static class CameraActivatorItem extends Item {
        public CameraActivatorItem(Settings settings) {
            super(settings);
        }

        @Override
        public void appendTooltip(ItemStack stack,
                                  TooltipContext context,
                                  TooltipDisplayComponent displayComponent,
                                  Consumer<Text> textConsumer,
                                  TooltipType type) {

            textConsumer.accept(Text.translatable("item.cameramod.camera_activator.tooltip.1")
                    .formatted(Formatting.GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_activator.tooltip.2")
                    .formatted(Formatting.DARK_GRAY));
        }

        @Override
        public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
            if (user.getWorld().isClient) return ActionResult.SUCCESS;

            if (entity instanceof CameraEntity) {
                CAMERA_COMMAND_STORAGE.put(user.getUuid(), entity.getUuid());
                if (user instanceof ServerPlayerEntity serverPlayer) {
                    ServerPlayNetworking.send(serverPlayer,
                            new CameraServerThing.BindCameraS2CPayload(entity.getUuid()));
                }
                user.sendMessage(Text.literal("Camera bound to you"), true);
                return ActionResult.SUCCESS;
            }
            user.sendMessage(Text.literal("Not a camera entity"), true);
            return ActionResult.PASS;
        }
    }

    // ==================== Camera Orienter ====================
    public static class CameraOrienterItem extends Item {
        public CameraOrienterItem(Settings settings) {
            super(settings);
        }

        @Override
        public void appendTooltip(ItemStack stack,
                                  TooltipContext context,
                                  TooltipDisplayComponent displayComponent,
                                  Consumer<Text> textConsumer,
                                  TooltipType type) {

            textConsumer.accept(Text.translatable("item.cameramod.camera_orienter.tooltip.1")
                    .formatted(Formatting.GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_orienter.tooltip.2")
                    .formatted(Formatting.DARK_GRAY));
        }

        @Override
        public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
            if (user.getWorld().isClient) return ActionResult.SUCCESS;
            if (entity instanceof CameraEntity cam) {
                cam.setYaw(user.getYaw());
                cam.setPitch(user.getPitch());
                cam.setHeadYaw(user.getYaw());
                cam.setBodyYaw(user.getYaw());
                user.sendMessage(Text.literal("Camera oriented"), true);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        }

        @Override
        public ActionResult use(World world, PlayerEntity player, Hand hand) {
            if (world.isClient || player == null) return ActionResult.SUCCESS;

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

            cam.setYaw(player.getYaw());
            cam.setPitch(player.getPitch());
            if (cam instanceof LivingEntity le) {
                le.setHeadYaw(player.getYaw());
                le.setBodyYaw(player.getYaw());
            }
            player.sendMessage(Text.literal("Camera oriented"), true);
            return ActionResult.SUCCESS;
        }
    }

    // ==================== Camera Mover ====================
    public static class CameraMoverItem extends Item {
        public CameraMoverItem(Settings settings) {
            super(settings);
        }

        @Override
        public void appendTooltip(ItemStack stack,
                                  TooltipContext context,
                                  TooltipDisplayComponent displayComponent,
                                  Consumer<Text> textConsumer,
                                  TooltipType type) {

            textConsumer.accept(Text.translatable("item.cameramod.camera_mover.tooltip.1")
                    .formatted(Formatting.GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_mover.tooltip.2")
                    .formatted(Formatting.DARK_GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_mover.tooltip.3")
                    .formatted(Formatting.DARK_GRAY));
        }

        @Override
        public ActionResult use(World world, PlayerEntity user, Hand hand) {
            if (world.isClient) return ActionResult.SUCCESS;

            UUID userId = user.getUuid();
            if (Boolean.TRUE.equals(CAMERA_MOVER_ACTIVENESS.get(userId))) {
                CAMERA_MOVER_DISTANCE.remove(userId);
                CAMERA_MOVER_ACTIVENESS.remove(userId);
                CAMERA_MOVER_UUIDS.remove(userId);
                user.sendMessage(Text.literal("Camera tracking stopped"), true);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        }

        @Override
        public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
            if (user.getWorld().isClient) return ActionResult.SUCCESS;
            if (!(entity instanceof CameraEntity)) return ActionResult.PASS;

            UUID userId = user.getUuid();
            Boolean active = CAMERA_MOVER_ACTIVENESS.get(userId);

            if (active == null || !active) {
                double dist = entity.getPos().distanceTo(user.getPos());
                CAMERA_MOVER_DISTANCE.put(userId, dist);
                CAMERA_MOVER_UUIDS.put(userId, entity.getUuid());
                CAMERA_MOVER_ACTIVENESS.put(userId, true);
                user.sendMessage(Text.literal("Camera following at distance " + String.format("%.1f", dist)), true);
                return ActionResult.SUCCESS;
            }

            // Already active — toggle off
            CAMERA_MOVER_DISTANCE.remove(userId);
            CAMERA_MOVER_ACTIVENESS.remove(userId);
            CAMERA_MOVER_UUIDS.remove(userId);
            user.sendMessage(Text.literal("Camera tracking stopped"), true);
            return ActionResult.SUCCESS;
        }
    }

    // ==================== Camera Fixer ====================
    // Shift+click a camera to select it, then click any entity to fix the camera to it.
    // Click a camera normally to un-fix it. Click air to check status.
    public static class CameraFixerItem extends Item {
        public CameraFixerItem(Settings settings) {
            super(settings);
        }

        @Override
        public void appendTooltip(ItemStack stack,
                                  TooltipContext context,
                                  TooltipDisplayComponent displayComponent,
                                  Consumer<Text> textConsumer,
                                  TooltipType type) {

            textConsumer.accept(Text.translatable("item.cameramod.camera_fixer.tooltip.1")
                    .formatted(Formatting.GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_fixer.tooltip.2")
                    .formatted(Formatting.DARK_GRAY));
            textConsumer.accept(Text.translatable("item.cameramod.camera_fixer.tooltip.3")
                    .formatted(Formatting.DARK_GRAY));
        }

        @Override
        public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
            if (user.getWorld().isClient) return ActionResult.SUCCESS;

            UUID userId = user.getUuid();

            if (entity instanceof CameraEntity cam) {
                if (user.isSneaking()) {
                    // Shift+click camera → select it for fixing
                    CAMERA_FIXER_SELECTION.put(userId, cam.getUuid());
                    user.sendMessage(Text.literal("Camera selected. Now click an entity to fix the camera to it."), true);
                    return ActionResult.SUCCESS;
                } else {
                    // Normal click camera → toggle tracking the player
                    if (cam.getFixedTargetUuid() != null && cam.getFixedTargetUuid().equals(user.getUuid())) {
                        cam.setFixedTargetUuid(null);
                        user.sendMessage(Text.literal("Camera tracking disabled"), true);
                    } else {
                        cam.setFixedTargetUuid(user.getUuid());
                        user.sendMessage(Text.literal("Camera now tracks you"), true);
                    }
                    return ActionResult.SUCCESS;
                }
            }

            // Clicked a non-camera entity — check if we have a selected camera
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

            // If we have a selection pending, clear it
            if (CAMERA_FIXER_SELECTION.containsKey(userId)) {
                CAMERA_FIXER_SELECTION.remove(userId);
                user.sendMessage(Text.literal("Camera selection cleared"), true);
                return ActionResult.SUCCESS;
            }

            // Show status
            user.sendMessage(Text.literal("Shift+click a camera to select, then click an entity to fix"), true);
            return ActionResult.SUCCESS;
        }
    }

    // ==================== Server Tick Handler ====================
    private static void onServerTick(MinecraftServer server) {
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

        // Camera Fixer: rotate cameras to face their fixedTarget (stored on entity)
        for (ServerWorld world : server.getWorlds()) {
            for (Entity entity : world.iterateEntities()) {
                if (!(entity instanceof CameraEntity cam)) continue;
                UUID targetUuid = cam.getFixedTargetUuid();
                if (targetUuid == null) continue;

                Entity target = world.getEntity(targetUuid);
                if (target == null) continue;

                double dx = target.getX() - cam.getX();
                double dy = target.getEyeY() - cam.getEyeY();
                double dz = target.getZ() - cam.getZ();
                double horizontal = Math.sqrt(dx * dx + dz * dz);

                float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
                float pitch = (float) (-(Math.atan2(dy, horizontal) * (180.0 / Math.PI)));

                cam.setYaw(yaw);
                cam.setPitch(pitch);
                cam.setHeadYaw(yaw);
                cam.setBodyYaw(yaw);
            }
        }
    }
}
