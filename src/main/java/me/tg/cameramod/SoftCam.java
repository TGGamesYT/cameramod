package me.tg.cameramod;

import com.sun.jna.*;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.INT_PTR;
import java.io.*;
import java.nio.file.Files;

/**
 * Softcam loader + JNA wrapper for the native Softcam Sender API.
 * Extracts DLL + installer to ./natives and only runs installer once.
 */
public class SoftCam {

    // Interface that maps directly to the native functions
    public interface SoftcamLibrary extends Library {
        Pointer scCreateCamera(int width, int height, float framerate);
        void scDeleteCamera(Pointer camera);
        void scSendFrame(Pointer camera, Pointer imageBits);
        boolean scWaitForConnection(Pointer camera, float timeout);
        boolean scIsConnected(Pointer camera);
    }

    private static SoftcamLibrary INSTANCE;

    private static final File NATIVE_DIR = new File(System.getenv("APPDATA"), ".minecraft_cameramod");
    private static final File REGISTERED_MARKER = new File(NATIVE_DIR, ".softcam_registered");

    /**
     * Initializes Softcam by extracting DLL + installer, installing the driver if needed, then loading DLL.
     */
    public static void initialize() {
        if (INSTANCE != null) return; // already loaded

        try {
            // Pick correct arch
            String os = System.getProperty("os.name").toLowerCase();
            String arch = System.getProperty("os.arch").toLowerCase();
            String subdir;

            if (os.contains("win")) {
                if (arch.contains("64")) subdir = "windows-x64";
                else subdir = "windows-x86";
            } else {
                throw new UnsupportedOperationException("Only Windows supported in this build (" + os + "/" + arch + ")");
            }

            if (!NATIVE_DIR.exists()) NATIVE_DIR.mkdirs();

            // Extract DLL
            String dllResource = "/natives/" + subdir + "/softcam.dll";
            File dllFile = extractResource(dllResource, "softcam.dll");

            // Extract installer
            String installerResource = "/natives/" + subdir + "/softcam_installer.exe";
            File installerFile = extractResource(installerResource, "softcam_installer.exe");

            // Run installer if not already registered
            if (!REGISTERED_MARKER.exists()) {
                System.out.println("Softcam driver not registered yet. Running installer...");
                installSoftcam(installerFile, dllFile);
                // Mark as installed
                REGISTERED_MARKER.createNewFile();
            } else {
                System.out.println("Softcam driver already registered, skipping installer.");
            }

            // Load DLL via JNA
            System.load(dllFile.getAbsolutePath());
            INSTANCE = Native.load(dllFile.getAbsolutePath(), SoftcamLibrary.class);

            System.out.println("Softcam DLL loaded from " + dllResource);

        } catch (IOException e) {
            throw new RuntimeException("Failed to install/load Softcam native library", e);
        }
    }

    private static File extractResource(String resourcePath, String filename) throws IOException {
        InputStream in = SoftCam.class.getResourceAsStream(resourcePath);
        if (in == null) throw new FileNotFoundException("Resource not found in jar: " + resourcePath);

        File outFile = new File(NATIVE_DIR, filename);

        // Only extract if missing
        if (!outFile.exists()) {
            Files.copy(in, outFile.toPath());
        }

        in.close();
        return outFile;
    }


    private static void installSoftcam(File installerFile, File dllFile) {
        String args = "register \"" + dllFile.getAbsolutePath() + "\"";
        System.out.println("Running Softcam installer with UAC: " + installerFile.getAbsolutePath() + " " + args);
        WindowsElevator.runAsAdmin(installerFile.getAbsolutePath(), args);
    }

    // Helper methods for convenience
    public static Pointer createCamera(int width, int height, float framerate) {
        return INSTANCE.scCreateCamera(width, height, framerate);
    }

    public static void deleteCamera(Pointer camera) {
        INSTANCE.scDeleteCamera(camera);
    }

    public static void sendFrame(Pointer camera, byte[] frame) {
        Memory mem = new Memory(frame.length);
        mem.write(0, frame, 0, frame.length);
        INSTANCE.scSendFrame(camera, mem);
    }

    public static boolean waitForConnection(Pointer camera, float timeout) {
        return INSTANCE.scWaitForConnection(camera, timeout);
    }

    public static boolean isConnected(Pointer camera) {
        return INSTANCE.scIsConnected(camera);
    }

    /**
     * Windows helper for running an executable with elevation (UAC prompt).
     */
    static class WindowsElevator {
        public static void runAsAdmin(String exePath, String args) {
            HWND hwnd = null; // no parent window
            String operation = "runas"; // triggers UAC
            String parameters = args;
            String directory = null;
            int showCmd = 1; // SW_SHOWNORMAL

            INT_PTR result = Shell32.INSTANCE.ShellExecute(
                    hwnd, operation, exePath, parameters, directory, showCmd);

            if (result.intValue() <= 32) {
                throw new RuntimeException("ShellExecute failed with code: " + result.intValue());
            }
        }
    }
}
