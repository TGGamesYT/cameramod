package me.tg.cameramod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import me.tg.cameramod.Cameramod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;

import java.io.File;
import java.io.IOException;

public class CameraManager {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static SimpleFramebuffer framebuffer;
    public static final Logger LOGGER = LogManager.getLogger(Cameramod.MOD_ID);
    static Boolean capturePending = false;
    static Entity pendingEntity;
    static Entity oldCamera;

    public static void init() {
        framebuffer = new SimpleFramebuffer("camera_pov_fb", WIDTH, HEIGHT, true);
        framebuffer.resize(WIDTH, HEIGHT);
        LOGGER.info("Framebuffer initialized {}x{}", WIDTH, HEIGHT);
    }

    /** Capture entity POV and save as PNG */
    public static void captureAndSend(Entity entity) {
        MinecraftClient mc = MinecraftClient.getInstance();

        // Save previous camera
        oldCamera = mc.cameraEntity;

        // Set entity as camera
        mc.cameraEntity = entity;
        mc.gameRenderer.getCamera().reset();

        // Queue capture for next tick
        pendingEntity = entity;
        capturePending = true;
    }

    static {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (capturePending && pendingEntity != null) {
                capturePending = false;

                MinecraftClient client = MinecraftClient.getInstance();
                Framebuffer framebuffer = client.getFramebuffer();
                File gameDir = client.runDirectory;

                try {
                    ScreenshotRecorder.saveScreenshot(gameDir, framebuffer, (Text msg) -> {
                        if (client.player != null) client.player.sendMessage(msg, false);
                    });
                } finally {
                    // Restore original camera
                    client.cameraEntity = oldCamera;
                    client.gameRenderer.getCamera().reset();
                }

                pendingEntity = null;
                oldCamera = null;
            }
        });
    }



}
