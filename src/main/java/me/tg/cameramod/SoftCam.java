package me.tg.cameramod;

import com.sun.jna.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;

/**
 * JNA wrapper for the Softcam virtual camera driver.
 * Extracts native DLL to APPDATA, registers the COM driver via regsvr32 once,
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
            syncResource("/natives/uninstall_camera.bat", "uninstall_camera.bat");

            File oldInstaller = new File(NATIVE_DIR, "softcam_installer.exe");
            if (oldInstaller.exists() && oldInstaller.delete()) {
                LOGGER.info("Deleted leftover softcam_installer.exe from appdata");
            }

            // Load the DLL right away so in-process camera API works even without
            // COM registration (registration only matters for external apps like OBS
            // to see the virtual camera as a device).
            System.load(dllFile.getAbsolutePath());
            INSTANCE = Native.load(dllFile.getAbsolutePath(), SoftcamLibrary.class);
            initialized = true;
            LOGGER.info("Softcam loaded ({}) from {}", subdir, dllFile.getAbsolutePath());

            // Registration prompt + UAC + restart in a background thread so the
            // game keeps loading while the user decides.
            if (!REGISTERED_MARKER.exists()) {
                final String dllPath = dllFile.getAbsolutePath();
                Thread t = new Thread(() -> runRegistrationPrompt(dllPath), "Cameramod-SoftcamRegister");
                t.setDaemon(true);
                t.start();
            }

        } catch (Exception e) {
            LOGGER.error("Failed to initialize Softcam native library", e);
        }
    }

    private static void runRegistrationPrompt(String dllPath) {
        try {
            LOGGER.info("Softcam driver not registered. Asking user (background thread)...");
            String markerPath = REGISTERED_MARKER.getAbsolutePath().replace("'", "''");
            int exitCode = new ProcessBuilder("powershell", "-Command",
                    "Add-Type -AssemblyName PresentationFramework; " +
                    "$ask = [System.Windows.MessageBox]::Show('Install the Minecraft Virtualcam driver? This lets the in-game camera show up as a webcam in OBS, Discord, browsers, etc. Requires admin rights and a system restart.', 'Minecraft Virtualcam', 'YesNo', 'Question'); " +
                    "if ($ask -ne 'Yes') { exit 2 } " +
                    "$registered = $false; " +
                    "do { try { Start-Process regsvr32 -ArgumentList '/s \"" + dllPath + "\"' -Verb runAs -Wait; $registered = $true } " +
                    "catch { $r = [System.Windows.MessageBox]::Show('Registration was denied or failed. Try again?', 'Minecraft Virtualcam', 'YesNo', 'Warning'); if ($r -ne 'Yes') { break } } } " +
                    "while (-not $registered); " +
                    "if ($registered) { " +
                    "  try { New-Item -ItemType File -Path '" + markerPath + "' -Force | Out-Null } catch {}; " +
                    "  $r2 = [System.Windows.MessageBox]::Show('Virtual camera registered. Restart your computer to apply changes. Restart now?', 'Minecraft Virtualcam', 'YesNo', 'Information'); " +
                    "  if ($r2 -eq 'Yes') { Start-Sleep -Milliseconds 500; Restart-Computer -Force }; " +
                    "  exit 0 " +
                    "} " +
                    "else { [System.Windows.MessageBox]::Show('Virtual camera was not registered. Restart Minecraft to try again.', 'Minecraft Virtualcam', 'OK', 'Warning'); exit 1 }"
            ).start().waitFor();
            // Fallback: create marker from Java side too (in case PS script couldn't write it)
            if (exitCode == 0 && !REGISTERED_MARKER.exists() && !REGISTERED_MARKER.createNewFile()) {
                LOGGER.warn("Could not create registration marker file");
            } else if (exitCode == 2) {
                LOGGER.info("User declined Softcam driver installation. Will ask again next launch.");
            }
        } catch (Exception e) {
            LOGGER.error("Softcam registration prompt failed", e);
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
            // Previous instance may still be held — wait briefly and retry
            LOGGER.warn("scCreateCamera returned null, retrying after delay...");
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            cam = INSTANCE.scCreateCamera(width, height, framerate);
        }
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

    private static void syncResource(String resourcePath, String filename) throws IOException {
        File outFile = new File(NATIVE_DIR, filename);
        try (InputStream in = SoftCam.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new FileNotFoundException("Resource not found in jar: " + resourcePath);
            byte[] jarBytes = in.readAllBytes();
            if (!outFile.exists() || !java.util.Arrays.equals(jarBytes, Files.readAllBytes(outFile.toPath()))) {
                Files.write(outFile.toPath(), jarBytes);
                LOGGER.info("Updated {} in appdata", filename);
            }
        }
    }

}
