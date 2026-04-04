package me.tg.cameramod;

import net.fabricmc.api.ModInitializer;
import com.sun.jna.Pointer;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.Color;

import me.tg.cameramod.SoftCam;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.*;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.entity.attribute.DefaultAttributeRegistry;

public class Cameramod implements ModInitializer {


    public static String MOD_ID = "cameramod";
    public static final RegistryKey<EntityType<?>> CAMERA_ENTITY_KEY =
            RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(MOD_ID, "camera"));
    public static final EntityType<CameraEntity> CAMERA_ENTITY_ENTITY_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "camera"),
            EntityType.Builder.create(CameraEntity::new, SpawnGroup.CREATURE).dimensions(0.75f, 1.5f).build(CAMERA_ENTITY_KEY)
    );
    public static final RegistryKey<ItemGroup> CUSTOM_ITEM_GROUP_KEY = RegistryKey.of(Registries.ITEM_GROUP.getKey(), Identifier.of(MOD_ID, "itemgroup"));
    public static final ItemGroup CUSTOM_ITEM_GROUP = FabricItemGroup.builder()
            .icon(() -> new ItemStack(ServerItems.CAMERA_ITEM))
            .displayName(Text.translatable("itemGroup.Cameramod"))
            .build();
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static int camwidth = 860;
    public static int camheight = 480;
    public static float camframerate = 20f;
    public static Pointer softcamCamera;
    @Override
    public void onInitialize() {
        // Run SoftCam in a background thread
        // test run:
        // new Thread(this::runSoftCamTest, "SoftCam-Test-Thread").start();
        SoftCam.initialize();

        softcamCamera = SoftCam.createCamera(camwidth, camheight, camframerate);
        FabricDefaultAttributeRegistry.register(CAMERA_ENTITY_ENTITY_TYPE, CameraEntity.createCameraAttributes());
        //Damn playS2C and playC2S are so similar
        PayloadTypeRegistry.playS2C().register(CameraServerThing.SetCameraS2CPayload.ID, CameraServerThing.SetCameraS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CameraServerThing.CameraFrameS2CPayload.ID, CameraServerThing.CameraFrameS2CPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CameraServerThing.CameraFrameS2CPayload.ID, CameraServerThing.CameraFrameS2CPayload.CODEC);
        CameraServerThing.register();
        ServerItems.registerItems();
        // Register the group.
        Registry.register(Registries.ITEM_GROUP, CUSTOM_ITEM_GROUP_KEY, CUSTOM_ITEM_GROUP);

        ItemGroupEvents.modifyEntriesEvent(CUSTOM_ITEM_GROUP_KEY).register(itemGroup -> {
            itemGroup.add(ServerItems.CAMERA_ITEM);
            itemGroup.add(ServerItems.CAMERA_ACTIVATOR);
            itemGroup.add(ServerItems.CAMERA_ORIENTER);
            itemGroup.add(ServerItems.CAMERA_MOVER);
            itemGroup.add(ServerItems.CAMERA_BINDER);
        });
    }

    /*private void runSoftCamTest() {
        try {
            SoftCam.initialize();

            // Create a virtual camera
            int width = 640;
            int height = 480;
            Pointer cam = SoftCam.createCamera(width, height, 30.0f);
            if (cam == null) {
                System.out.println("Failed to create SoftCam camera!");
                return;
            }

            System.out.println("SoftCam camera created successfully!");

            // Wait until a program connects
            boolean connected = SoftCam.waitForConnection(cam, 10.0f);
            System.out.println("Camera connected: " + connected);

            // Prepare a BufferedImage for drawing
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();

            long startTime = System.currentTimeMillis();
            long duration = 30_000; // 30 seconds
            long frameInterval = 1000 / 30; // ~30 fps

            while (System.currentTimeMillis() - startTime < duration) {
                // Clear frame
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, width, height);

                // Draw red circle in the middle
                g.setColor(Color.RED);
                int radius = 50;
                g.fillOval(width / 2 - radius, height / 2 - radius, radius * 2, radius * 2);

                // Convert BufferedImage → byte array (RGBA)
                byte[] frameBytes = new byte[width * height * 4];
                int index = 0;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int rgb = image.getRGB(x, y);
                        frameBytes[index++] = (byte) ((rgb >> 16) & 0xFF); // R
                        frameBytes[index++] = (byte) ((rgb >> 8) & 0xFF);  // G
                        frameBytes[index++] = (byte) (rgb & 0xFF);         // B
                        frameBytes[index++] = (byte) ((rgb >> 24) & 0xFF); // A
                    }
                }

                // Send frame
                SoftCam.sendFrame(cam, frameBytes);

                Thread.sleep(frameInterval);
            }

            // Cleanup
            g.dispose();
            SoftCam.deleteCamera(cam);
            System.out.println("SoftCam camera stopped after 30 seconds.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }*/
}
