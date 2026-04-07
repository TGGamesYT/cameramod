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
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // Tells a client which camera entity is bound to their virtualcam
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

    // Tells a client to unbind their virtualcam
    public record UnbindCameraS2CPayload() implements CustomPayload {
        public static final Identifier UNBIND_CAMERA_ID = Identifier.of(Cameramod.MOD_ID, "unbind_camera");
        public static final Id<UnbindCameraS2CPayload> ID = new Id<>(UNBIND_CAMERA_ID);
        public static final PacketCodec<RegistryByteBuf, UnbindCameraS2CPayload> CODEC =
                PacketCodec.unit(new UnbindCameraS2CPayload());

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }
}
