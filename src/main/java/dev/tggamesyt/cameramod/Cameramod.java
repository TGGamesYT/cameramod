package dev.tggamesyt.cameramod;

import com.sun.jna.Pointer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.*;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.world.rule.GameRule;
import net.minecraft.world.rule.GameRuleCategory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Cameramod implements ModInitializer {

    public static final String MOD_ID = "cameramod";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static final RegistryKey<EntityType<?>> CAMERA_ENTITY_KEY =
            RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(MOD_ID, "camera"));

    public static final EntityType<CameraEntity> CAMERA_ENTITY_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "camera"),
            EntityType.Builder.create(CameraEntity::new, SpawnGroup.CREATURE)
                    .dimensions(0.75f, 1.5f)
                    .maxTrackingRange(256)
                    .build(CAMERA_ENTITY_KEY)
    );

    public static final RegistryKey<ItemGroup> CUSTOM_ITEM_GROUP_KEY =
            RegistryKey.of(Registries.ITEM_GROUP.getKey(), Identifier.of(MOD_ID, "itemgroup"));

    public static final ItemGroup CUSTOM_ITEM_GROUP = FabricItemGroup.builder()
            .icon(() -> new ItemStack(ServerItems.CAMERA_ITEM))
            .displayName(Text.translatable("itemGroup.Cameramod"))
            .build();

    public static final GameRule<Boolean> CAMERA_SEES_CHAT =
            GameRuleBuilder.forBoolean(false).category(GameRuleCategory.MISC)
                    .buildAndRegister(Identifier.of(MOD_ID, "camera_sees_chat"));

    public static final GameRule<Boolean> CAMERA_FLIPPED =
            GameRuleBuilder.forBoolean(false).category(GameRuleCategory.MISC)
                    .buildAndRegister(Identifier.of(MOD_ID, "camera_flipped"));

    public static final GameRule<Boolean> CAMERA_NAME_TAGS =
            GameRuleBuilder.forBoolean(true).category(GameRuleCategory.MISC)
                    .buildAndRegister(Identifier.of(MOD_ID, "camera_name_tags"));

    public static final GameRule<Boolean> CAMERA_GUI_MODE =
            GameRuleBuilder.forBoolean(false).category(GameRuleCategory.MISC)
                    .buildAndRegister(Identifier.of(MOD_ID, "camera_gui_mode"));

    public static final GameRule<Boolean> CAMERA_SHOW_PLAYER_GUIS =
            GameRuleBuilder.forBoolean(false).category(GameRuleCategory.MISC)
                    .buildAndRegister(Identifier.of(MOD_ID, "camera_show_player_guis"));

    // Max FPS gates. Render at max(stream, virtual); SoftCam send is
    // further gated by VIRTUAL_FPS so feeding it faster than its driver
    // wants doesn't burn JNI calls. Both default to 30.
    public static final GameRule<Integer> CAMERA_STREAM_FPS =
            GameRuleBuilder.forInteger(30).range(1, 240).category(GameRuleCategory.MISC)
                    .buildAndRegister(Identifier.of(MOD_ID, "camera_stream_fps"));

    public static final GameRule<Integer> CAMERA_VIRTUAL_FPS =
            GameRuleBuilder.forInteger(30).range(1, 240).category(GameRuleCategory.MISC)
                    .buildAndRegister(Identifier.of(MOD_ID, "camera_virtual_fps"));

    // Bootstrap defaults; CameramodClient.onInitializeClient overwrites these
    // with the primary monitor's resolution (capped at Full HD) before the
    // virtual camera is created.
    public static int camwidth = 1280;
    public static int camheight = 720;
    public static float camframerate = 30f;
    public static Pointer softcamCamera;

    @Override
    public void onInitialize() {
        // SoftCam is initialized on the client side only (CameramodClient)

        FabricDefaultAttributeRegistry.register(CAMERA_ENTITY_ENTITY_TYPE, CameraEntity.createCameraAttributes());

        // S2C packets
        PayloadTypeRegistry.playS2C().register(CameraServerThing.SetCameraS2CPayload.ID, CameraServerThing.SetCameraS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CameraServerThing.BindCameraS2CPayload.ID, CameraServerThing.BindCameraS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CameraServerThing.UnbindCameraS2CPayload.ID, CameraServerThing.UnbindCameraS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CameraServerThing.CameraItemStateS2CPayload.ID, CameraServerThing.CameraItemStateS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CameraServerThing.CameraIntSettingS2CPayload.ID, CameraServerThing.CameraIntSettingS2CPayload.CODEC);

        // C2S packets
        PayloadTypeRegistry.playC2S().register(CameraServerThing.CameraScrollC2SPayload.ID, CameraServerThing.CameraScrollC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CameraServerThing.CameraOrientC2SPayload.ID, CameraServerThing.CameraOrientC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CameraServerThing.CameraItemUseC2SPayload.ID, CameraServerThing.CameraItemUseC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CameraServerThing.CameraEditC2SPayload.ID, CameraServerThing.CameraEditC2SPayload.CODEC);

        CameraServerThing.register();
        ServerItems.registerItems();

        Registry.register(Registries.ITEM_GROUP, CUSTOM_ITEM_GROUP_KEY, CUSTOM_ITEM_GROUP);

        ItemGroupEvents.modifyEntriesEvent(CUSTOM_ITEM_GROUP_KEY).register(itemGroup -> {
            itemGroup.add(ServerItems.CAMERA_ITEM);
            itemGroup.add(ServerItems.CAMERA_ACTIVATOR);
            itemGroup.add(ServerItems.CAMERA_ORIENTER);
            itemGroup.add(ServerItems.CAMERA_MOVER);
            itemGroup.add(ServerItems.CAMERA_FIXER);
            itemGroup.add(ServerItems.CAMERA_ZOOMER);
            itemGroup.add(ServerItems.CAMERA_GRAVITY);
            itemGroup.add(ServerItems.CAMERA_ATTACHER);
            itemGroup.add(ServerItems.CAMERA_REMOVER);
        });
    }
}
