package me.tg.cameramod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.item.v1.FabricItem;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.Spawner;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.*;
import java.util.function.Function;

import me.tg.cameramod.CameraEntity; // replace with your camera entity class
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

import static me.tg.cameramod.Cameramod.CAMERA_ENTITY_ENTITY_TYPE;

public class ServerItems {

    // ---- Items ----
    static RegistryKey<Item> camera_orienter_key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Cameramod.MOD_ID, "camera_orienter"));
    public static final Item CAMERA_ORIENTER = new CameraOrienterItem(new Item.Settings().maxCount(1).registryKey(camera_orienter_key));
    static RegistryKey<Item> camera_activator_key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Cameramod.MOD_ID, "camera_activator"));
    public static final Item CAMERA_ACTIVATOR = new CameraActivatorItem(new Item.Settings().maxCount(1).registryKey(camera_activator_key));
    static RegistryKey<Item> camera_mover_key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Cameramod.MOD_ID, "camera_mover"));
    public static final Item CAMERA_MOVER = new CameraMoverItem(new Item.Settings().maxCount(1).registryKey(camera_mover_key));
    static RegistryKey<Item> camera_binder_key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Cameramod.MOD_ID, "camera_fixer"));
    public static final Item CAMERA_BINDER = new CameraBinderItem(new Item.Settings().maxCount(1).registryKey(camera_binder_key));
    static RegistryKey<Item> camera_item_key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(Cameramod.MOD_ID, "camera_item"));
    public static final Item CAMERA_ITEM = new CameraItem(new Item.Settings().maxCount(1).registryKey(camera_item_key));

    // Stores bound camera UUID per player
    public static final WeakHashMap<UUID, UUID> CAMERA_COMMAND_STORAGE = new WeakHashMap<>();
    public static void addToCommandStorage(UUID firstUUID, UUID secondUUID) {

        // Remove existing entry if key already exists
        CAMERA_COMMAND_STORAGE.entrySet().removeIf(entry -> entry.getKey().equals(firstUUID));

        // Add the new pair
        CAMERA_COMMAND_STORAGE.put(firstUUID, secondUUID);
    }
    public static final WeakHashMap<UUID, UUID> CAMERA_BINDINGS = new WeakHashMap<>();

    // Stores temporary distance for Camera Mover per player
    public static final WeakHashMap<UUID, Double> CAMERA_MOVER_DISTANCE = new WeakHashMap<>();
    public static final WeakHashMap<UUID, Boolean> CAMERA_MOVER_ACTIVENESS = new WeakHashMap<>();
    public static final WeakHashMap<UUID, UUID> CAMERA_MOVER_UUIDS = new WeakHashMap<>();

    public static void registerItems() {
        Registry.register(Registries.ITEM, camera_orienter_key, CAMERA_ORIENTER);
        Registry.register(Registries.ITEM, camera_activator_key, CAMERA_ACTIVATOR);
        Registry.register(Registries.ITEM, camera_mover_key, CAMERA_MOVER);
        Registry.register(Registries.ITEM, camera_binder_key, CAMERA_BINDER);
        Registry.register(Registries.ITEM, camera_item_key, CAMERA_ITEM);
        ServerTickEvents.START_SERVER_TICK.register(ServerItems::onServerTick);
    }
    public static class CameraItem extends Item {

        public CameraItem(Settings settings) {
            super(settings);
        }

        public ActionResult useOnBlock(ItemUsageContext context) {
            World world = context.getWorld();
            if (world.isClient) {
                return ActionResult.SUCCESS;
            } else {
                ItemStack itemStack = context.getStack();
                BlockPos blockPos = context.getBlockPos();
                Direction direction = context.getSide();
                BlockState blockState = world.getBlockState(blockPos);
                    BlockPos blockPos2;
                    if (blockState.getCollisionShape(world, blockPos).isEmpty()) {
                        blockPos2 = blockPos;
                    } else {
                        blockPos2 = blockPos.offset(direction);
                    }

                if (((EntityType<?>) CAMERA_ENTITY_ENTITY_TYPE).spawnFromItemStack((ServerWorld)world, itemStack, context.getPlayer(), blockPos2, SpawnReason.SPAWN_ITEM_USE, true, !Objects.equals(blockPos, blockPos2) && direction == Direction.UP) != null) {
                        itemStack.decrement(1);
                        world.emitGameEvent(context.getPlayer(), GameEvent.ENTITY_PLACE, blockPos);
                    }

                    return ActionResult.SUCCESS;

            }
        }

        public ActionResult use(World world, PlayerEntity user, Hand hand) {
            ItemStack itemStack = user.getStackInHand(hand);
            BlockHitResult blockHitResult = raycast(world, user, RaycastContext.FluidHandling.SOURCE_ONLY);
            if (blockHitResult.getType() != HitResult.Type.BLOCK) {
                return ActionResult.PASS;
            } else if (world instanceof ServerWorld) {
                ServerWorld serverWorld = (ServerWorld)world;
                BlockPos blockPos = blockHitResult.getBlockPos();
                if (!(world.getBlockState(blockPos).getBlock() instanceof FluidBlock)) {
                    return ActionResult.PASS;
                } else if (world.canEntityModifyAt(user, blockPos) && user.canPlaceOn(blockPos, blockHitResult.getSide(), itemStack)) {
                    Entity entity = ((EntityType<?>) CAMERA_ENTITY_ENTITY_TYPE).spawnFromItemStack(serverWorld, itemStack, user, blockPos, SpawnReason.SPAWN_ITEM_USE, false, false);
                    if (entity == null) {
                        return ActionResult.PASS;
                    } else {
                        itemStack.decrementUnlessCreative(1, user);
                        user.incrementStat(Stats.USED.getOrCreateStat(this));
                        world.emitGameEvent(user, GameEvent.ENTITY_PLACE, entity.getPos());
                        return ActionResult.SUCCESS;
                    }
                } else {
                    return ActionResult.FAIL;
                }
            } else {
                return ActionResult.SUCCESS;
            }
        }
    }
    // ------------------ ITEM CLASSES ------------------

    // 1. Camera Orienter
    public static class CameraOrienterItem extends Item {
        public CameraOrienterItem(Settings settings) { super(settings); }

        @Override
        public ActionResult use(World world, PlayerEntity player, Hand hand) {
            if (!world.isClient && player != null) {
                // Expand player bounding box to radius 16
                Box box = player.getBoundingBox().expand(8);

                // Find nearest camera entity
                List<CameraEntity> cameras = world.getEntitiesByType(
                        TypeFilter.instanceOf(CameraEntity.class), // filter for CameraEntity
                        box,
                        cam -> true // accept all
                );

                if (!cameras.isEmpty()) {
                    // Find the closest
                    CameraEntity nearest = cameras.stream()
                            .min(Comparator.comparingDouble(c -> c.getBlockPos().getSquaredDistance(player.getBlockPos())))
                            .orElse(null);

                    float yaw = player.getYaw();
                    float pitch = player.getPitch();
                    nearest.setYaw(yaw);
                    nearest.setPitch(pitch);
                }
            }
            return ActionResult.SUCCESS;
        }
    }

    // 2. Camera Activator
    public static class CameraActivatorItem extends Item {
        public CameraActivatorItem(Settings settings) { super(settings); }

        @Override
        public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
            if (entity instanceof CameraEntity) {
                addToCommandStorage(user.getUuid(), entity.getUuid());
            }
            return ActionResult.PASS;
        }
    }
    public static double[] calculateTargetPos(BlockPos start, double distance, Vec3d rotation) {
        // Normalize the rotation vector so it only indicates direction
        Vec3d dir = rotation.normalize();

        // Scale by the distance
        Vec3d offset = dir.multiply(distance, distance, distance);

        // Add the offset to the start position
        Vec3d target = new Vec3d(start.getX(), start.getY(), start.getZ()).add(offset);

        // Convert back to BlockPos (rounding to nearest block coordinates)
        return new double[] { target.x, target.y, target.z };
    }
    public static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // 3. Camera Mover
    public static class CameraMoverItem extends Item {
        public CameraMoverItem(Settings settings) { super(settings); }
        @Override
        public ActionResult use(World world, PlayerEntity user, Hand hand) {
            UUID userId = user.getUuid();
            Boolean active = CAMERA_MOVER_ACTIVENESS.get(userId);
            if (Boolean.TRUE.equals(active)) {
                CAMERA_MOVER_DISTANCE.remove(userId);
                CAMERA_MOVER_ACTIVENESS.remove(userId);
                CAMERA_MOVER_UUIDS.remove(userId);
                CAMERA_MOVER_ACTIVENESS.put(userId, false);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        }

        @Override
        public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
            UUID userId = user.getUuid();
            Boolean active = CAMERA_MOVER_ACTIVENESS.get(userId);

            if (!(entity instanceof CameraEntity)) return ActionResult.PASS;
            if (active == null || !active) {
                CAMERA_MOVER_DISTANCE.remove(userId);
                CAMERA_MOVER_ACTIVENESS.remove(userId);
                CAMERA_MOVER_UUIDS.remove(userId);
                double distance = distance(entity.getX(), entity.getY(), entity.getZ(), user.getX(), user.getY(), user.getZ());
                CAMERA_MOVER_DISTANCE.put(userId, distance);
                CAMERA_MOVER_UUIDS.put(userId, entity.getUuid());
                CAMERA_MOVER_ACTIVENESS.put(userId, true);
            }
            return ActionResult.PASS;
        }
    }

    private static void onServerTick(MinecraftServer server) {
        // Loop through all players on the server
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            Boolean active = CAMERA_MOVER_ACTIVENESS.get(player.getUuid());
            if (Boolean.TRUE.equals(active)) {

                Double distance = CAMERA_MOVER_DISTANCE.get(player.getUuid());
                UUID camUuid = CAMERA_MOVER_UUIDS.get(player.getUuid());
                Entity camera = player.getWorld().getEntity(camUuid);

                if (camera != null && distance != null) {
                    double[] pos = calculateTargetPos(player.getBlockPos(), distance, player.getRotationVector());
                    camera.setPos(pos[0], pos[1], pos[2]);
                }
            }
        }

    }


    // 4. Camera Binder
    public static class CameraBinderItem extends Item {
        public CameraBinderItem(Settings settings) { super(settings); }

        @Override
        public ActionResult use(World world, PlayerEntity user, Hand hand) {
            //TODO: pls make this work 🙏
            return ActionResult.SUCCESS;
        }

    }
}
