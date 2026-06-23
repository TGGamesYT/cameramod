package dev.tggamesyt.cameramod;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.UUID;
import java.util.function.Supplier;

public final class CameraServerThing {

    public static void register() {
        registerCommand();
        registerScrollHandler();
        registerOrientHandler();
        registerCameraItemUseHandler();
        registerEditHandler();
    }

    private static void registerCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    CommandManager.literal("setcamera")
                            .executes(ctx -> {
                                ServerPlayerEntity executor = ctx.getSource().getPlayer();
                                UUID entityUUID = ServerItems.CAMERA_COMMAND_STORAGE.get(executor.getUuid());
                                if (entityUUID == null) {
                                    ctx.getSource().sendError(Text.literal("No camera bound. Use Camera Activator on a camera first."));
                                    return 0;
                                }
                                return runSetCamera(ctx.getSource(), executor.getName().getString(), entityUUID);
                            })
                            .then(CommandManager.argument("firstArg", StringArgumentType.string())
                                    .executes(ctx -> {
                                        ServerPlayerEntity executor = ctx.getSource().getPlayer();
                                        String arg = StringArgumentType.getString(ctx, "firstArg");

                                        ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(arg);
                                        UUID entityUUID;

                                        if (target != null) {
                                            entityUUID = ServerItems.CAMERA_COMMAND_STORAGE.get(target.getUuid());
                                            if (entityUUID == null) {
                                                ctx.getSource().sendError(Text.literal("No camera bound for player: " + target.getName().getString()));
                                                return 0;
                                            }
                                            return runSetCamera(ctx.getSource(), target.getName().getString(), entityUUID);
                                        } else {
                                            try {
                                                entityUUID = UUID.fromString(arg);
                                            } catch (IllegalArgumentException e) {
                                                ctx.getSource().sendError(Text.literal("Invalid UUID: " + arg));
                                                return 0;
                                            }
                                            return runSetCamera(ctx.getSource(), executor.getName().getString(), entityUUID);
                                        }
                                    })
                            )
                            .then(CommandManager.argument("player", EntityArgumentType.player())
                                    .then(CommandManager.argument("entityUuid", EntityArgumentType.entity())
                                            .executes(ctx -> {
                                                ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
                                                UUID entityUUID = EntityArgumentType.getEntity(ctx, "entityUuid").getUuid();
                                                return runSetCamera(ctx.getSource(), player.getName().getString(), entityUUID);
                                            })
                                    )
                            )
            );
        });
    }

    private static void registerScrollHandler() {
        ServerPlayNetworking.registerGlobalReceiver(CameraScrollC2SPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                if (payload.type == 0) {
                    // Mover distance adjustment
                    ServerItems.handleMoverScroll(player, payload.delta);
                } else if (payload.type == 1) {
                    // Zoomer zoom adjustment
                    ServerItems.handleZoomerScroll(player, payload.delta);
                } else if (payload.type == 2) {
                    // Clear zoomer target
                    ServerItems.clearZoomerTarget(player);
                }
            });
        });
    }

    private static int runSetCamera(ServerCommandSource source, String playerName, UUID entityUuid) {
        MinecraftServer server = source.getServer();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            source.sendFeedback((Supplier<Text>) () -> Text.literal("Player not found: " + playerName), false);
            return 0;
        }
        SetCameraS2CPayload payload = new SetCameraS2CPayload(entityUuid);
        ServerPlayNetworking.send(player, payload);

        source.sendFeedback((Supplier<Text>) () -> Text.literal("Sent camera request to " + playerName), false);
        return 1;
    }

    // ==================== S2C Payloads ====================

    public record SetCameraS2CPayload(UUID uuid) implements CustomPayload {
        public static final PacketCodec<ByteBuf, UUID> PACKET_CODEC = new PacketCodec<ByteBuf, UUID>() {
            public UUID decode(ByteBuf byteBuf) {
                return PacketByteBuf.readUuid(byteBuf);
            }
            public void encode(ByteBuf byteBuf, UUID uuid) {
                PacketByteBuf.writeUuid(byteBuf, uuid);
            }
        };
        public static final Identifier SET_CAMERA_ID = Identifier.of(Cameramod.MOD_ID, "set_camera");
        public static final Id<SetCameraS2CPayload> ID = new Id<>(SET_CAMERA_ID);
        public static final PacketCodec<RegistryByteBuf, SetCameraS2CPayload> CODEC =
                PacketCodec.tuple(PACKET_CODEC, SetCameraS2CPayload::uuid, SetCameraS2CPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record BindCameraS2CPayload(UUID cameraUuid) implements CustomPayload {
        public static final PacketCodec<ByteBuf, UUID> UUID_CODEC = new PacketCodec<ByteBuf, UUID>() {
            public UUID decode(ByteBuf byteBuf) { return PacketByteBuf.readUuid(byteBuf); }
            public void encode(ByteBuf byteBuf, UUID uuid) { PacketByteBuf.writeUuid(byteBuf, uuid); }
        };
        public static final Identifier BIND_CAMERA_ID = Identifier.of(Cameramod.MOD_ID, "bind_camera");
        public static final Id<BindCameraS2CPayload> ID = new Id<>(BIND_CAMERA_ID);
        public static final PacketCodec<RegistryByteBuf, BindCameraS2CPayload> CODEC =
                PacketCodec.tuple(UUID_CODEC, BindCameraS2CPayload::cameraUuid, BindCameraS2CPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record UnbindCameraS2CPayload() implements CustomPayload {
        public static final Identifier UNBIND_CAMERA_ID = Identifier.of(Cameramod.MOD_ID, "unbind_camera");
        public static final Id<UnbindCameraS2CPayload> ID = new Id<>(UNBIND_CAMERA_ID);
        public static final PacketCodec<RegistryByteBuf, UnbindCameraS2CPayload> CODEC =
                PacketCodec.unit(new UnbindCameraS2CPayload());

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    // Tells client whether mover/zoomer is active (type 0=mover, 1=zoomer)
    public record CameraItemStateS2CPayload(byte type, boolean active) implements CustomPayload {
        public static final Identifier STATE_ID = Identifier.of(Cameramod.MOD_ID, "camera_item_state");
        public static final Id<CameraItemStateS2CPayload> ID = new Id<>(STATE_ID);
        public static final PacketCodec<RegistryByteBuf, CameraItemStateS2CPayload> CODEC = new PacketCodec<>() {
            @Override
            public CameraItemStateS2CPayload decode(RegistryByteBuf buf) {
                return new CameraItemStateS2CPayload(buf.readByte(), buf.readBoolean());
            }
            @Override
            public void encode(RegistryByteBuf buf, CameraItemStateS2CPayload payload) {
                buf.writeByte(payload.type);
                buf.writeBoolean(payload.active);
            }
        };
        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    // Server-to-client sync of integer-valued settings (type 0 = stream FPS,
    // type 1 = virtual cam FPS). Booleans go through CameraItemStateS2CPayload.
    public record CameraIntSettingS2CPayload(byte type, int value) implements CustomPayload {
        public static final Identifier INT_SETTING_ID = Identifier.of(Cameramod.MOD_ID, "camera_int_setting");
        public static final Id<CameraIntSettingS2CPayload> ID = new Id<>(INT_SETTING_ID);
        public static final PacketCodec<RegistryByteBuf, CameraIntSettingS2CPayload> CODEC = new PacketCodec<>() {
            @Override
            public CameraIntSettingS2CPayload decode(RegistryByteBuf buf) {
                return new CameraIntSettingS2CPayload(buf.readByte(), buf.readVarInt());
            }
            @Override
            public void encode(RegistryByteBuf buf, CameraIntSettingS2CPayload payload) {
                buf.writeByte(payload.type);
                buf.writeVarInt(payload.value);
            }
        };
        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    // Periodic camera position sync (for when entity is beyond tracking range)
    public record CameraPosS2CPayload(double x, double y, double z, float yaw, float pitch, float zoom) implements CustomPayload {
        public static final Identifier POS_ID = Identifier.of(Cameramod.MOD_ID, "camera_pos");
        public static final Id<CameraPosS2CPayload> ID = new Id<>(POS_ID);
        public static final PacketCodec<RegistryByteBuf, CameraPosS2CPayload> CODEC = new PacketCodec<>() {
            @Override
            public CameraPosS2CPayload decode(RegistryByteBuf buf) {
                return new CameraPosS2CPayload(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readFloat(), buf.readFloat(), buf.readFloat());
            }
            @Override
            public void encode(RegistryByteBuf buf, CameraPosS2CPayload payload) {
                buf.writeDouble(payload.x);
                buf.writeDouble(payload.y);
                buf.writeDouble(payload.z);
                buf.writeFloat(payload.yaw);
                buf.writeFloat(payload.pitch);
                buf.writeFloat(payload.zoom);
            }
        };
        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    private static void registerOrientHandler() {
        ServerPlayNetworking.registerGlobalReceiver(CameraOrientC2SPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                net.minecraft.server.world.ServerWorld world =
                        (net.minecraft.server.world.ServerWorld) context.player().getWorld();
                net.minecraft.entity.Entity entity = world.getEntity(payload.cameraUuid());
                if (entity instanceof CameraEntity cam) {
                    cam.setYaw(payload.yaw());
                    cam.setPitch(payload.pitch());
                    cam.setHeadYaw(payload.yaw());
                    cam.setBodyYaw(payload.yaw());
                }
            });
        });
    }

    // ==================== C2S Payloads ====================

    // Scroll event from client (type 0=mover distance, 1=zoomer zoom)
    public record CameraScrollC2SPayload(byte type, float delta) implements CustomPayload {
        public static final Identifier SCROLL_ID = Identifier.of(Cameramod.MOD_ID, "camera_scroll");
        public static final Id<CameraScrollC2SPayload> ID = new Id<>(SCROLL_ID);
        public static final PacketCodec<RegistryByteBuf, CameraScrollC2SPayload> CODEC = new PacketCodec<>() {
            @Override
            public CameraScrollC2SPayload decode(RegistryByteBuf buf) {
                return new CameraScrollC2SPayload(buf.readByte(), buf.readFloat());
            }
            @Override
            public void encode(RegistryByteBuf buf, CameraScrollC2SPayload payload) {
                buf.writeByte(payload.type);
                buf.writeFloat(payload.delta);
            }
        };
        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    // Syncs camera yaw/pitch to server when the fixer is removed client-side
    public record CameraOrientC2SPayload(UUID cameraUuid, float yaw, float pitch) implements CustomPayload {
        public static final Identifier ORIENT_ID = Identifier.of(Cameramod.MOD_ID, "camera_orient");
        public static final Id<CameraOrientC2SPayload> ID = new Id<>(ORIENT_ID);
        public static final PacketCodec<RegistryByteBuf, CameraOrientC2SPayload> CODEC = new PacketCodec<>() {
            @Override
            public CameraOrientC2SPayload decode(RegistryByteBuf buf) {
                return new CameraOrientC2SPayload(PacketByteBuf.readUuid(buf), buf.readFloat(), buf.readFloat());
            }
            @Override
            public void encode(RegistryByteBuf buf, CameraOrientC2SPayload payload) {
                PacketByteBuf.writeUuid(buf, payload.cameraUuid);
                buf.writeFloat(payload.yaw);
                buf.writeFloat(payload.pitch);
            }
        };
        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * Camera item use from camera-mode hotbar.
     * itemSlot: 0=place, 1=activator, 2=orienter, 3=mover, 4=fixer, 5=zoomer, 6=gravity, 7=attacher, 8=remover
     * actionType: item-specific sub-action (see handler switch)
     * cameraUuid / targetUuid: involved entities; nil UUID (0,0) means "none"
     * blockX/Y/Z + blockFace: for camera placement on block
     */
    public record CameraItemUseC2SPayload(
            byte itemSlot, byte actionType,
            UUID cameraUuid, UUID targetUuid,
            int blockX, int blockY, int blockZ, byte blockFace
    ) implements CustomPayload {
        public static final UUID NIL = new UUID(0, 0);
        public static final Identifier USE_ID = Identifier.of(Cameramod.MOD_ID, "camera_item_use");
        public static final Id<CameraItemUseC2SPayload> ID = new Id<>(USE_ID);
        public static final PacketCodec<RegistryByteBuf, CameraItemUseC2SPayload> CODEC = new PacketCodec<>() {
            @Override
            public CameraItemUseC2SPayload decode(RegistryByteBuf buf) {
                return new CameraItemUseC2SPayload(
                        buf.readByte(), buf.readByte(),
                        PacketByteBuf.readUuid(buf), PacketByteBuf.readUuid(buf),
                        buf.readInt(), buf.readInt(), buf.readInt(), buf.readByte());
            }
            @Override
            public void encode(RegistryByteBuf buf, CameraItemUseC2SPayload p) {
                buf.writeByte(p.itemSlot); buf.writeByte(p.actionType);
                PacketByteBuf.writeUuid(buf, p.cameraUuid != null ? p.cameraUuid : NIL);
                PacketByteBuf.writeUuid(buf, p.targetUuid != null ? p.targetUuid : NIL);
                buf.writeInt(p.blockX); buf.writeInt(p.blockY); buf.writeInt(p.blockZ);
                buf.writeByte(p.blockFace);
            }
        };
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * General-purpose camera property editor sent from the GUI.
     * Flags indicate which fields are set; NIL UUID means "clear target".
     */
    public record CameraEditC2SPayload(
            UUID cameraUuid, int flags,
            double posX, double posY, double posZ,
            float yaw, float pitch,
            UUID fixedTargetUuid, byte fixerMode,
            UUID attachTargetUuid,
            float attachOffsetX, float attachOffsetY, float attachOffsetZ,
            byte attachMode,
            boolean gravityEnabled, float zoomLevel, String customName
    ) implements CustomPayload {
        public static final int FLAG_POS         = 1;
        public static final int FLAG_ROTATION    = 2;
        public static final int FLAG_FIXED       = 4;
        public static final int FLAG_FIXER_MODE  = 8;
        public static final int FLAG_ATTACH      = 16;
        public static final int FLAG_ATTACH_OFF  = 32;
        public static final int FLAG_ATTACH_MODE = 64;
        public static final int FLAG_GRAVITY     = 128;
        public static final int FLAG_ZOOM        = 256;
        public static final int FLAG_NAME        = 512;

        public static final Identifier EDIT_ID = Identifier.of(Cameramod.MOD_ID, "camera_edit");
        public static final Id<CameraEditC2SPayload> ID = new Id<>(EDIT_ID);
        public static final PacketCodec<RegistryByteBuf, CameraEditC2SPayload> CODEC = new PacketCodec<>() {
            @Override
            public CameraEditC2SPayload decode(RegistryByteBuf buf) {
                return new CameraEditC2SPayload(
                        PacketByteBuf.readUuid(buf), buf.readInt(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readFloat(), buf.readFloat(),
                        PacketByteBuf.readUuid(buf), buf.readByte(),
                        PacketByteBuf.readUuid(buf),
                        buf.readFloat(), buf.readFloat(), buf.readFloat(),
                        buf.readByte(), buf.readBoolean(), buf.readFloat(),
                        buf.readString());
            }
            @Override
            public void encode(RegistryByteBuf buf, CameraEditC2SPayload p) {
                UUID nil = CameraItemUseC2SPayload.NIL;
                PacketByteBuf.writeUuid(buf, p.cameraUuid);
                buf.writeInt(p.flags);
                buf.writeDouble(p.posX); buf.writeDouble(p.posY); buf.writeDouble(p.posZ);
                buf.writeFloat(p.yaw); buf.writeFloat(p.pitch);
                PacketByteBuf.writeUuid(buf, p.fixedTargetUuid != null ? p.fixedTargetUuid : nil);
                buf.writeByte(p.fixerMode);
                PacketByteBuf.writeUuid(buf, p.attachTargetUuid != null ? p.attachTargetUuid : nil);
                buf.writeFloat(p.attachOffsetX); buf.writeFloat(p.attachOffsetY); buf.writeFloat(p.attachOffsetZ);
                buf.writeByte(p.attachMode);
                buf.writeBoolean(p.gravityEnabled);
                buf.writeFloat(p.zoomLevel);
                buf.writeString(p.customName != null ? p.customName : "");
            }
        };
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    private static void registerEditHandler() {
        ServerPlayNetworking.registerGlobalReceiver(CameraEditC2SPayload.ID, (payload, context) ->
                context.server().execute(() -> handleCameraEdit(payload, context.player())));
    }

    private static void handleCameraEdit(CameraEditC2SPayload p, ServerPlayerEntity player) {
        net.minecraft.server.world.ServerWorld world = (net.minecraft.server.world.ServerWorld) player.getWorld();
        net.minecraft.entity.Entity ent = world.getEntity(p.cameraUuid());
        if (!(ent instanceof CameraEntity cam)) return;

        UUID nil = CameraItemUseC2SPayload.NIL;
        int f = p.flags();

        if ((f & CameraEditC2SPayload.FLAG_POS) != 0)
            cam.requestTeleport(p.posX(), p.posY(), p.posZ());
        if ((f & CameraEditC2SPayload.FLAG_ROTATION) != 0) {
            cam.setYaw(p.yaw()); cam.setPitch(p.pitch());
            cam.setHeadYaw(p.yaw()); cam.setBodyYaw(p.yaw());
        }
        if ((f & CameraEditC2SPayload.FLAG_FIXED) != 0)
            cam.setFixedTargetUuid(p.fixedTargetUuid().equals(nil) ? null : p.fixedTargetUuid());
        if ((f & CameraEditC2SPayload.FLAG_FIXER_MODE) != 0)
            cam.setFixerMode(p.fixerMode());
        if ((f & CameraEditC2SPayload.FLAG_ATTACH) != 0) {
            UUID at = p.attachTargetUuid().equals(nil) ? null : p.attachTargetUuid();
            if (at != null && cam.getAttachTargetUuid() == null) {
                net.minecraft.entity.Entity target = world.getEntity(at);
                if (target != null) cam.setAttachOffset(cam.getPos().subtract(target.getPos()));
            }
            cam.setAttachTargetUuid(at);
        }
        if ((f & CameraEditC2SPayload.FLAG_ATTACH_OFF) != 0)
            cam.setAttachOffset(new net.minecraft.util.math.Vec3d(p.attachOffsetX(), p.attachOffsetY(), p.attachOffsetZ()));
        if ((f & CameraEditC2SPayload.FLAG_ATTACH_MODE) != 0)
            cam.setAttachMode(p.attachMode());
        if ((f & CameraEditC2SPayload.FLAG_GRAVITY) != 0)
            cam.setGravityEnabled(p.gravityEnabled());
        if ((f & CameraEditC2SPayload.FLAG_ZOOM) != 0)
            cam.setZoomLevel(p.zoomLevel());
        if ((f & CameraEditC2SPayload.FLAG_NAME) != 0 && !p.customName().isBlank()) {
            cam.setCustomName(net.minecraft.text.Text.literal(p.customName()));
            cam.setCustomNameVisible(true);
        }
    }

    private static void registerCameraItemUseHandler() {
        ServerPlayNetworking.registerGlobalReceiver(CameraItemUseC2SPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> handleCameraItemUse(payload, player));
        });
    }

    private static void handleCameraItemUse(CameraItemUseC2SPayload p, ServerPlayerEntity player) {
        net.minecraft.server.world.ServerWorld world = (net.minecraft.server.world.ServerWorld) player.getWorld();
        UUID nilUuid = CameraItemUseC2SPayload.NIL;

        switch (p.itemSlot()) {
            case 0 -> { // Camera item — place camera
                if (p.actionType() == 0) { // place on block
                    net.minecraft.util.math.Direction face = net.minecraft.util.math.Direction.values()[p.blockFace() & 7];
                    net.minecraft.util.math.BlockPos blockPos = new net.minecraft.util.math.BlockPos(p.blockX(), p.blockY(), p.blockZ());
                    net.minecraft.util.math.BlockPos spawnPos = blockPos.offset(face);
                    CameraEntity cam = new CameraEntity(Cameramod.CAMERA_ENTITY_ENTITY_TYPE, world);
                    cam.refreshPositionAndAngles(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, player.getYaw(), 0);
                    world.spawnEntity(cam);
                    player.sendMessage(net.minecraft.text.Text.literal("Camera placed"), true);
                } else if (p.actionType() == 2) { // place at player position (from GUI)
                    CameraEntity cam = new CameraEntity(Cameramod.CAMERA_ENTITY_ENTITY_TYPE, world);
                    if (!p.cameraUuid().equals(nilUuid)) cam.setUuid(p.cameraUuid());
                    cam.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(),
                            player.getYaw(), player.getPitch());
                    // blockFace bit 0 = "spawn without gravity" (player was flying)
                    if ((p.blockFace() & 1) != 0) cam.setGravityEnabled(false);
                    world.spawnEntity(cam);
                    player.sendMessage(net.minecraft.text.Text.literal("Camera placed at player"), true);
                }
            }
            case 1 -> { // Activator
                if (p.actionType() == 0) { // toggle streaming (restores saved cam if any)
                    ServerItems.CAMERA_ACTIVATOR.use(world, player, net.minecraft.util.Hand.MAIN_HAND);
                } else if (p.actionType() == 2) { // toggle streaming, always player POV (no re-bind)
                    UUID userId = player.getUuid();
                    boolean isStreamingOn = Boolean.TRUE.equals(ServerItems.STREAMING_ENABLED.get(userId));
                    UUID active = ServerItems.CAMERA_COMMAND_STORAGE.get(userId);
                    if (active != null) {
                        ServerItems.CAMERA_COMMAND_STORAGE.remove(userId);
                        ServerItems.clearForcedChunks(world, active);
                        ServerPlayNetworking.send(player, new CameraServerThing.UnbindCameraS2CPayload());
                    }
                    // Clear "last bound" so a later activator press doesn't restore it either.
                    player.removeAttached(ServerItems.SAVED_CAMERA_ATTACHMENT);
                    boolean nextOn = !isStreamingOn;
                    ServerItems.STREAMING_ENABLED.put(userId, nextOn);
                    ServerPlayNetworking.send(player, new CameraServerThing.CameraItemStateS2CPayload((byte) 2, nextOn));
                    player.sendMessage(net.minecraft.text.Text.literal(
                            nextOn ? "Streaming enabled (player POV)" : "Streaming disabled"), true);
                } else if (p.actionType() == 1) { // bind specific camera
                    if (!p.cameraUuid().equals(nilUuid)) {
                        net.minecraft.entity.Entity ent = world.getEntity(p.cameraUuid());
                        if (ent instanceof CameraEntity cam) {
                            UUID userId = player.getUuid();
                            UUID bound = ServerItems.CAMERA_COMMAND_STORAGE.get(userId);
                            if (cam.getUuid().equals(bound)) {
                                ServerItems.CAMERA_COMMAND_STORAGE.remove(userId);
                                ServerItems.STREAMING_ENABLED.put(userId, false);
                                ServerPlayNetworking.send(player, new CameraServerThing.CameraItemStateS2CPayload((byte) 2, false));
                                ServerPlayNetworking.send(player, new CameraServerThing.UnbindCameraS2CPayload());
                                ServerItems.clearForcedChunks(world, cam.getUuid());
                                player.removeAttached(ServerItems.SAVED_CAMERA_ATTACHMENT);
                                player.sendMessage(net.minecraft.text.Text.literal("Camera unbound"), true);
                            } else {
                                ServerItems.CAMERA_COMMAND_STORAGE.put(userId, cam.getUuid());
                                ServerItems.STREAMING_ENABLED.put(userId, true);
                                ServerPlayNetworking.send(player, new CameraServerThing.CameraItemStateS2CPayload((byte) 2, true));
                                ServerPlayNetworking.send(player, new CameraServerThing.BindCameraS2CPayload(cam.getUuid()));
                                player.setAttached(ServerItems.SAVED_CAMERA_ATTACHMENT, cam.getUuid().toString());
                                player.sendMessage(net.minecraft.text.Text.literal("Camera bound"), true);
                            }
                        }
                    }
                }
            }
            case 2 -> { // Orienter
                UUID camUuid = p.cameraUuid().equals(nilUuid) ? ServerItems.CAMERA_COMMAND_STORAGE.get(player.getUuid()) : p.cameraUuid();
                if (camUuid != null) {
                    net.minecraft.entity.Entity ent = world.getEntity(camUuid);
                    if (ent instanceof CameraEntity cam) {
                        cam.setYaw(player.getYaw()); cam.setPitch(player.getPitch());
                        cam.setHeadYaw(player.getYaw()); cam.setBodyYaw(player.getYaw());
                        player.sendMessage(net.minecraft.text.Text.literal("Camera oriented"), true);
                    }
                }
            }
            case 3 -> { // Mover
                UUID userId = player.getUuid();
                if (p.actionType() == 0 && !p.cameraUuid().equals(nilUuid)) { // start mover
                    net.minecraft.entity.Entity ent = world.getEntity(p.cameraUuid());
                    if (ent instanceof CameraEntity cam) {
                        double dist = cam.getPos().distanceTo(player.getPos());
                        ServerItems.CAMERA_MOVER_DISTANCE.put(userId, dist);
                        ServerItems.CAMERA_MOVER_UUIDS.put(userId, cam.getUuid());
                        ServerItems.CAMERA_MOVER_ACTIVENESS.put(userId, true);
                        cam.setBeingMoved(true);
                        cam.setMoverPlayerUuid(userId);
                        cam.setMoverDistance((float) dist);
                        ServerPlayNetworking.send(player, new CameraServerThing.CameraItemStateS2CPayload((byte) 0, true));
                        player.sendMessage(net.minecraft.text.Text.literal("Camera mover started"), true);
                    }
                } else if (p.actionType() == 1) { // stop mover
                    UUID camUuid2 = ServerItems.CAMERA_MOVER_UUIDS.get(userId);
                    if (camUuid2 != null) {
                        net.minecraft.entity.Entity ent = world.getEntity(camUuid2);
                        if (ent instanceof CameraEntity ce) {
                            ce.setBeingMoved(false);
                            ce.setMoverPlayerUuid(null);
                        }
                    }
                    ServerItems.CAMERA_MOVER_DISTANCE.remove(userId);
                    ServerItems.CAMERA_MOVER_ACTIVENESS.remove(userId);
                    ServerItems.CAMERA_MOVER_UUIDS.remove(userId);
                    ServerPlayNetworking.send(player, new CameraServerThing.CameraItemStateS2CPayload((byte) 0, false));
                    player.sendMessage(net.minecraft.text.Text.literal("Camera mover stopped"), true);
                }
            }
            case 4 -> { // Fixer
                if (!p.cameraUuid().equals(nilUuid)) {
                    net.minecraft.entity.Entity ent = world.getEntity(p.cameraUuid());
                    if (ent instanceof CameraEntity cam) {
                        if (p.actionType() == 0) { // toggle fix to player self
                            if (player.getUuid().equals(cam.getFixedTargetUuid())) {
                                cam.setFixedTargetUuid(null);
                                player.sendMessage(net.minecraft.text.Text.literal("Camera tracking disabled"), true);
                            } else {
                                cam.setFixedTargetUuid(player.getUuid());
                                player.sendMessage(net.minecraft.text.Text.literal("Camera now tracks you"), true);
                            }
                        } else if (p.actionType() == 1 && !p.targetUuid().equals(nilUuid)) { // fix to specific target
                            cam.setFixedTargetUuid(p.targetUuid());
                            player.sendMessage(net.minecraft.text.Text.literal("Camera fixed to target"), true);
                        } else if (p.actionType() == 2) { // clear fix
                            cam.setFixedTargetUuid(null);
                            player.sendMessage(net.minecraft.text.Text.literal("Camera tracking disabled"), true);
                        } else if (p.actionType() == 3) { // toggle fixer mode
                            byte newMode = (byte) ((cam.getFixerMode() + 1) % 2);
                            cam.setFixerMode(newMode);
                            player.sendMessage(net.minecraft.text.Text.literal("Fixer mode: " + (newMode == 0 ? "Look At" : "Look Same Way")), true);
                        }
                    }
                }
            }
            case 5 -> { // Zoomer
                UUID userId5 = player.getUuid();
                if (p.actionType() == 0 && !p.cameraUuid().equals(nilUuid)) { // set zoom target
                    net.minecraft.entity.Entity ent = world.getEntity(p.cameraUuid());
                    if (ent instanceof CameraEntity cam) {
                        ServerItems.CAMERA_ZOOMER_UUIDS.put(userId5, cam.getUuid());
                        ServerPlayNetworking.send(player, new CameraServerThing.CameraItemStateS2CPayload((byte) 1, true));
                        player.sendMessage(net.minecraft.text.Text.literal("Zoom target set (" + String.format("%.2f", cam.getZoomLevel()) + "x)"), true);
                    }
                } else if (p.actionType() == 1) { // clear zoom target
                    ServerItems.CAMERA_ZOOMER_UUIDS.remove(userId5);
                    ServerPlayNetworking.send(player, new CameraServerThing.CameraItemStateS2CPayload((byte) 1, false));
                    player.sendMessage(net.minecraft.text.Text.literal("Zoom target cleared"), true);
                }
            }
            case 6 -> { // Gravity
                if (!p.cameraUuid().equals(nilUuid)) {
                    net.minecraft.entity.Entity ent = world.getEntity(p.cameraUuid());
                    if (ent instanceof CameraEntity cam) {
                        boolean ng = !cam.isGravityEnabled();
                        cam.setGravityEnabled(ng);
                        player.sendMessage(net.minecraft.text.Text.literal("Camera gravity: " + (ng ? "ON" : "OFF")), true);
                    }
                }
            }
            case 7 -> { // Attacher
                if (!p.cameraUuid().equals(nilUuid)) {
                    net.minecraft.entity.Entity ent = world.getEntity(p.cameraUuid());
                    if (ent instanceof CameraEntity cam) {
                        if (p.actionType() == 0) { // toggle attach to self
                            if (player.getUuid().equals(cam.getAttachTargetUuid())) {
                                cam.setAttachTargetUuid(null);
                                player.sendMessage(net.minecraft.text.Text.literal("Camera detached"), true);
                            } else {
                                cam.setAttachTargetUuid(player.getUuid());
                                cam.setAttachOffset(cam.getPos().subtract(player.getPos()));
                                player.sendMessage(net.minecraft.text.Text.literal("Camera attached to you"), true);
                            }
                        } else if (p.actionType() == 1 && !p.targetUuid().equals(nilUuid)) { // attach to target
                            net.minecraft.entity.Entity target = world.getEntity(p.targetUuid());
                            if (target != null) {
                                cam.setAttachTargetUuid(p.targetUuid());
                                cam.setAttachOffset(cam.getPos().subtract(target.getPos()));
                                player.sendMessage(net.minecraft.text.Text.literal("Camera attached to " + target.getName().getString()), true);
                            }
                        } else if (p.actionType() == 2) { // detach
                            cam.setAttachTargetUuid(null);
                            player.sendMessage(net.minecraft.text.Text.literal("Camera detached"), true);
                        } else if (p.actionType() == 3) { // toggle attach mode
                            if (cam.getAttachTargetUuid() != null) {
                                net.minecraft.entity.Entity target = world.getEntity(cam.getAttachTargetUuid());
                                if (target != null) {
                                    byte newMode = (byte) ((cam.getAttachMode() + 1) % 2);
                                    net.minecraft.util.math.Vec3d off = cam.getAttachOffset();
                                    float yawRad = (float) (target.getYaw() * Math.PI / 180.0);
                                    if (newMode == 1) {
                                        double lx =  off.x * Math.cos(yawRad) + off.z * Math.sin(yawRad);
                                        double lz = -off.x * Math.sin(yawRad) + off.z * Math.cos(yawRad);
                                        cam.setAttachOffset(new net.minecraft.util.math.Vec3d(lx, off.y, lz));
                                    } else {
                                        double wx = off.x * Math.cos(yawRad) - off.z * Math.sin(yawRad);
                                        double wz = off.x * Math.sin(yawRad) + off.z * Math.cos(yawRad);
                                        cam.setAttachOffset(new net.minecraft.util.math.Vec3d(wx, off.y, wz));
                                    }
                                    cam.setAttachMode(newMode);
                                    player.sendMessage(net.minecraft.text.Text.literal("Attach mode: " + (newMode == 0 ? "World" : "Head")), true);
                                }
                            }
                        }
                    }
                }
            }
            case 8 -> { // Remover
                if (!p.cameraUuid().equals(nilUuid)) {
                    net.minecraft.entity.Entity ent = world.getEntity(p.cameraUuid());
                    if (ent instanceof CameraEntity cam) {
                        UUID camId = cam.getUuid();
                        // Unbind any players bound to this camera
                        for (ServerPlayerEntity other : world.getServer().getPlayerManager().getPlayerList()) {
                            if (camId.equals(ServerItems.CAMERA_COMMAND_STORAGE.get(other.getUuid()))) {
                                ServerItems.CAMERA_COMMAND_STORAGE.remove(other.getUuid());
                                ServerItems.STREAMING_ENABLED.put(other.getUuid(), false);
                                ServerPlayNetworking.send(other, new CameraServerThing.CameraItemStateS2CPayload((byte) 2, false));
                                ServerPlayNetworking.send(other, new CameraServerThing.UnbindCameraS2CPayload());
                                ServerItems.clearForcedChunks(world, camId);
                                other.removeAttached(ServerItems.SAVED_CAMERA_ATTACHMENT);
                            }
                        }
                        cam.remove(net.minecraft.entity.Entity.RemovalReason.KILLED);
                        player.sendMessage(net.minecraft.text.Text.literal("Camera removed"), true);
                    }
                }
            }
        }
    }
}
