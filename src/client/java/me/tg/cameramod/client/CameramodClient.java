package me.tg.cameramod.client;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import me.tg.cameramod.CameraServerThing;
import me.tg.cameramod.Cameramod;
import me.tg.cameramod.SoftCam;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.buffers.GpuBuffer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.math.Box;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Mouse;

import static me.tg.cameramod.Cameramod.softcamCamera;

public class CameramodClient implements ClientModInitializer {

    public static final Logger LOGGER = LogManager.getLogger(Cameramod.MOD_ID);
    public static final EntityModelLayer MODEL_CAMERA_LAYER = new EntityModelLayer(Identifier.of("camera"), "main");
    public static boolean isCamera = false;
    public static boolean originalF1State = false;
    public static boolean originalpausestate = true;
    byte[] frameBytes;


    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(Cameramod.CAMERA_ENTITY_ENTITY_TYPE, CameraEntityRenderer::new);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null) return;

            Box box = new Box(
                    -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
                    Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE
            );

            for (Entity camera : client.world.getEntitiesByType(
                    Cameramod.CAMERA_ENTITY_ENTITY_TYPE,
                    box,
                    e -> true // your predicate
            )) {
                CameraManager.captureAndSend(camera);
            }
        });
        ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Called once when joining a world
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && mc.world != null) {
                LOGGER.info("Client joined world: " + mc.world.getRegistryKey().getValue());
                // Call your method here
                CameraManager.init();
            }
        });
        EntityModelLayerRegistry.registerModelLayer(MODEL_CAMERA_LAYER, CameraEntityModel::getTexturedModelData);
        ClientPlayNetworking.registerGlobalReceiver(CameraServerThing.CameraFrameS2CPayload.ID, (payload, context) -> {
            SoftCam.sendFrame(softcamCamera, payload.bytes());
        });

        ClientPlayNetworking.registerGlobalReceiver(CameraServerThing.SetCameraS2CPayload.ID, (payload, context) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            Mouse mouse = mc.mouse;
            if (isCamera) {
                isCamera = false;
                mc.cameraEntity = context.player();
                mc.gameRenderer.getCamera().reset();
                mc.options.hudHidden = originalF1State;
                mc.options.pauseOnLostFocus = originalpausestate;
            } else {
                isCamera = true;
                mc.cameraEntity = context.player().getWorld().getEntity(payload.uuid());
                mc.gameRenderer.getCamera().reset();
                originalF1State = mc.options.hudHidden;
                originalpausestate = mc.options.pauseOnLostFocus;
                mc.options.pauseOnLostFocus = false;
                mc.options.hudHidden = true;
                mouse.unlockCursor();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (isCamera) {client.mouse.unlockCursor();}
            //System.out.println("after cursor");
            if (client == null || client.getFramebuffer() == null || client.world == null || !isCamera) return;

            Framebuffer framebuffer = client.getFramebuffer();
            GpuTexture gpuTexture = framebuffer.getColorAttachment();
            if (gpuTexture == null) return;

            int width = framebuffer.textureWidth;
            int height = framebuffer.textureHeight;

            GpuBuffer gpuBuffer = RenderSystem.getDevice().createBuffer(() -> "Camera buffer", 9, width * height * gpuTexture.getFormat().pixelSize());
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(gpuTexture, gpuBuffer, 0, () -> {
                TextureFormat format = gpuTexture.getFormat();
                int pixelSize = format.pixelSize(); // should be 4
                int rowPitch = width * pixelSize;   // ideally, but sometimes GPU aligns this to 256 bytes

                try (GpuBuffer.MappedView mappedView = encoder.mapBuffer(gpuBuffer, true, false)) {
                    ByteBuffer src = mappedView.data();

                    int expected = width * height * 4;
                    int stride = src.capacity() / height;
                    if (stride != width * 4) {
                        System.out.println("GPU buffer padded: stride=" + stride);
                    }

                    final int softcamWidth = Cameramod.camwidth;
                    final int softcamHeight = Cameramod.camheight;
                    final int targetRowLength = softcamWidth * 3; // BGR24
                    final int frameSize = targetRowLength * softcamHeight;

                    if (frameBytes == null || frameBytes.length != frameSize)
                        frameBytes = new byte[frameSize];

                    Arrays.fill(frameBytes, (byte) 0); // ensure blank background

                    // Fill from Minecraft framebuffer (854x480) into SoftCam buffer (860x480)
                    for (int y = 0; y < Math.min(height, softcamHeight); y++) {
                        int srcRowStart = y * stride;
                        int dstRowStart = (softcamHeight - 1 - y) * targetRowLength;

                        for (int x = 0; x < width && x < softcamWidth; x++) {
                            int srcIndex = srcRowStart + x * 4;
                            int dstIndex = dstRowStart + x * 3;

                            byte r = src.get(srcIndex);
                            byte g = src.get(srcIndex + 1);
                            byte b = src.get(srcIndex + 2);

                            frameBytes[dstIndex]     = b;
                            frameBytes[dstIndex + 1] = g;
                            frameBytes[dstIndex + 2] = r;
                        }
                        // Right side (6 missing pixels) already black from fill()
                    }

                    SoftCam.sendFrame(softcamCamera, frameBytes);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    gpuBuffer.close();
                }
            }, 0);
        });
    }

    private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                  CommandRegistryAccess registryAccess) {
        dispatcher.register(
                ClientCommandManager.literal("camshot")
                        .executes(ctx -> executeCamshot(ctx))
        );
    }

    private int executeCamshot(CommandContext<?> ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            LOGGER.warn("Client player or world is null");
            return 0;
        }

        // Make a 50×50×50 box around the player
        double range = 25.0;
        Box box = client.player.getBoundingBox().expand(range);

        LOGGER.info("recieved");

        var entities = client.world.getEntitiesByType(
                Cameramod.CAMERA_ENTITY_ENTITY_TYPE,
                box,
                e -> true
        );

        LOGGER.info("found {} camera entities", entities.size());

        for (Entity camera : entities) {
            LOGGER.info("sending camera {}", camera.getUuidAsString());
            CameraManager.captureAndSend(camera);
        }
        return 1;
    }
}
