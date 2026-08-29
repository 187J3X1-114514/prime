package dev.prime.render.vulkan.natives;

import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class NativeLibraries {
    public static final Path EXTRACTED_NATIVE_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("prime")
            .resolve("libraries");
    public static final String BUNDLED_NATIVE_PATH = "/prime/natives/";


    public static final NativeLibrary NATIVE_DLSSRR_BRIDGE;
    public static final NativeLibrary NATIVE_DLSSRR_FEATURE;
    public static final NativeLibrary NATIVE_NRD;
    public static final NativeLibrary NATIVE_FFXFSR;


    static {
        if (isWindowsX64()){
            NATIVE_DLSSRR_BRIDGE = createLibrary("prime_dlss_rr.dll","Prime DLSS RR Bridge");
            NATIVE_DLSSRR_FEATURE = createLibrary("nvngx_dlssd.dll","Prime DLSS RR Feature");
            NATIVE_NRD = createLibrary("prime_nrd.dll","Prime NRD Library");
            NATIVE_FFXFSR = createLibrary("amd_fidelityfx_vk.dll","Prime FidelityFX Library");
        } else {
            // TODO
            NATIVE_DLSSRR_BRIDGE = null;
            NATIVE_DLSSRR_FEATURE = null;
            NATIVE_NRD = null;
            NATIVE_FFXFSR = null;
        }
    }

    private NativeLibraries() {

    }

    private static NativeLibrary createLibrary(
            String fileName,
            String label
    ) {
        return new NativeLibrary(
                fileName,
                EXTRACTED_NATIVE_PATH,
                getBundledNativePath() + fileName,
                label
        );
    }

    public static String getBundledNativePath() {
        if (isWindowsX64()) {
            return BUNDLED_NATIVE_PATH + "windows-x86_64/";
        } else {
            return BUNDLED_NATIVE_PATH + "linux-x86_64/";
        }
    }

    public static boolean isWindowsX64() {
        return isWindowsX64(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
    }

    public static boolean isWindowsX64(String osName, String architecture) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        return os.startsWith("windows")
                && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    public static long requireFunction(
            SharedLibrary library, String functionName, String libraryName) {
        long address = library.getFunctionAddress(functionName);
        if (address == MemoryUtil.NULL) {
            throw new IllegalStateException(
                    libraryName + " is missing " + functionName);
        }
        return address;
    }

    public static void checkResult(int result, String operation) {
        if (result != 0) {
            throw new IllegalStateException(operation + " failed with native result " + result);
        }
    }
}
