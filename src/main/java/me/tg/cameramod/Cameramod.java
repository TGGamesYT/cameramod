package me.tg.cameramod;

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
import net.minecraft.world.GameRules;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
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
                    .build(CAMERA_ENTITY_KEY)
    );

    public static final RegistryKey<ItemGroup> CUSTOM_ITEM_GROUP_KEY =
            RegistryKey.of(Registries.ITEM_GROUP.getKey(), Identifier.of(MOD_ID, "itemgroup"));

    public static final ItemGroup CUSTOM_ITEM_GROUP = FabricItemGroup.builder()
            .icon(() -> new ItemStack(ServerItems.CAMERA_ITEM))
            .displayName(Text.translatable("itemGroup.Cameramod"))
            .build();

    public static final GameRules.Key<GameRules.BooleanRule> CAMERA_SEES_CHAT =
            GameRuleRegistry.register("cameraSeesChat", GameRules.Category.MISC, GameRuleFactory.createBooleanRule(false));

    public static int camwidth = 860;
    public static int camheight = 480;
    public static float camframerate = 20f;
    public static Pointer softcamCamera;

    @Override
    public void onInitialize() {
        SoftCam.initialize();
        softcamCamera = SoftCam.createCamera(camwidth, camheight, camframerate);

        FabricDefaultAttributeRegistry.register(CAMERA_ENTITY_ENTITY_TYPE, CameraEntity.createCameraAttributes());

        PayloadTypeRegistry.playS2C().register(CameraServerThing.SetCameraS2CPayload.ID, CameraServerThing.SetCameraS2CPayload.CODEC);

        CameraServerThing.register();
        ServerItems.registerItems();

        Registry.register(Registries.ITEM_GROUP, CUSTOM_ITEM_GROUP_KEY, CUSTOM_ITEM_GROUP);

        ItemGroupEvents.modifyEntriesEvent(CUSTOM_ITEM_GROUP_KEY).register(itemGroup -> {
            itemGroup.add(ServerItems.CAMERA_ITEM);
            itemGroup.add(ServerItems.CAMERA_ACTIVATOR);
            itemGroup.add(ServerItems.CAMERA_ORIENTER);
            itemGroup.add(ServerItems.CAMERA_MOVER);
            itemGroup.add(ServerItems.CAMERA_FIXER);
        });
    }
}
