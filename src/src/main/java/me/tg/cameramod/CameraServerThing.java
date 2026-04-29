package me.tg.cameramod;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.EntityArgumentType;
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
}
