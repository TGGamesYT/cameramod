package me.tg.cameramod;

import com.sun.jna.*;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.INT_PTR;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;

/**
 * JNA wrapper for the Softcam virtual camera driver.
 * Extracts native DLL + installer to APPDATA, registers the COM driver once,
 * then provides a clean Java API for creating cameras and sending frames.
 */
public class SoftCam {

    private static final Logger LOGGER = LogManager.getLogger("cameramod/SoftCam");

    public interface SoftcamLibrary extends Library {
        Pointer scCreateCamera(int width, int height, float framerate);
        void scDeleteCamera(Pointer camera);
        void scSendFrame(Pointer camera, Pointer imageBits);
        boolean scWaitForConnection(Pointer camera, float timeout);
        boolean scIsConnected(Pointer camera);
    }

    private static SoftcamLibrary INSTANCE;
    private static boolean initialized = false;

    private static final File NATIVE_DIR = new File(System.getenv("APPDATA"), ".minecraft_cameramod");
    private static final File REGISTERED_MARKER = new File(NATIVE_DIR, ".softcam_registered");

    // Reusable JNA Memory buffer to avoid allocating on every frame
    private static Memory frameMemory;
    private static int frameMemorySize;

    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Extracts natives, registers the COM driver if needed, and loads the DLL.
     * Safe to call multiple times - only initializes once.
     */
    public static void initialize() {
        if (initialized) return;

        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            LOGGER.warn("SoftCam only supports Windows (detected: {}). Virtual camera disabled.", os);
            return;
        }

        try {
            String arch = System.getProperty("os.arch", "").toLowerCase();
            String subdir = arch.contains("64") ? "windows-x64" : "windows-x86";

            if (!NATIVE_DIR.exists() && !NATIVE_DIR.mkdirs()) {
                throw new IOException("Failed to create native directory: " + NATIVE_DIR);
            }

            File dllFile = extractResource("/natives/" + subdir + "/softcam.dll", "softcam.dll");
            File installerFile = extractResource("/natives/" + subdir + "/softcam_installer.exe", "softcam_installer.exe");
            extractResource("/natives/uninstall_camera.bat", "uninstall_camera.bat");

            if (!REGISTERED_MARKER.exists()) {
                LOGGER.info("Softcam driver not registered. Running installer with UAC...");
                runAsAdmin(installerFile.getAbsolutePath(),
                        "register \"" + dllFile.getAbsolutePath() + "\"");
                if (!REGISTERED_MARKER.createNewFile()) {
                    LOGGER.warn("Could not create registration marker file");
                }
            }

            System.load(dllFile.getAbsolutePath());
            INSTANCE = Native.load(dllFile.getAbsolutePath(), SoftcamLibrary.class);
            initialized = true;
            LOGGER.info("Softcam loaded ({}) from {}", subdir, dllFile.getAbsolutePath());

        } catch (Exception e) {
            LOGGER.error("Failed to initialize Softcam native library", e);
        }
    }

    // ---- Public API ----

    public static Pointer createCamera(int width, int height, float framerate) {
        if (!initialized) {
            LOGGER.warn("createCamera called but SoftCam is not initialized");
            return null;
        }
        Pointer cam = INSTANCE.scCreateCamera(width, height, framerate);
        if (cam == null) {
            LOGGER.error("scCreateCamera returned null ({}x{} @ {}fps)", width, height, framerate);
        }
        return cam;
    }

    public static void deleteCamera(Pointer camera) {
        if (!initialized || camera == null) return;
        INSTANCE.scDeleteCamera(camera);
    }

    /**
     * Sends a BGR24 frame to the virtual camera.
     * Reuses an internal JNA Memory buffer to avoid per-frame allocation.
     */
    public static void sendFrame(Pointer camera, byte[] frame) {
        if (!initialized || camera == null || frame == null) return;

        int len = frame.length;
        if (frameMemory == null || frameMemorySize != len) {
            frameMemory = new Memory(len);
            frameMemorySize = len;
        }
        frameMemory.write(0, frame, 0, len);
        INSTANCE.scSendFrame(camera, frameMemory);
    }

    public static boolean waitForConnection(Pointer camera, float timeout) {
        if (!initialized || camera == null) return false;
        return INSTANCE.scWaitForConnection(camera, timeout);
    }

    public static boolean isConnected(Pointer camera) {
        if (!initialized || camera == null) return false;
        return INSTANCE.scIsConnected(camera);
    }

    // ---- Internal helpers ----

    private static File extractResource(String resourcePath, String filename) throws IOException {
        File outFile = new File(NATIVE_DIR, filename);
        try (InputStream in = SoftCam.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new FileNotFoundException("Resource not found in jar: " + resourcePath);
            if (!outFile.exists()) {
                Files.copy(in, outFile.toPath());
            }
        }
        return outFile;
    }

    private static void runAsAdmin(String exePath, String args) {
        LOGGER.info("UAC elevation: {} {}", exePath, args);
        INT_PTR result = Shell32.INSTANCE.ShellExecute(
                (HWND) null, "runas", exePath, args, null, 1 /* SW_SHOWNORMAL */);
        if (result.intValue() <= 32) {
            throw new RuntimeException("ShellExecute failed with code " + result.intValue());
        }
    }
}
