package dev.prime.render.vulkan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

/** Shared platform, extraction and symbol checks for bundled native libraries. */
public final class NativeLibraries {
    private NativeLibraries() {
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

    public static SharedLibrary loadBundled(
            String cacheName,
            String resourceName,
            String fileName,
            String label) {
        byte[] bytes = readResource(resourceName, label);
        Path directory = NativeRuntimeFiles.directory(cacheName, bytes);
        Path libraryPath = directory.resolve(fileName);
        NativeRuntimeFiles.publish(libraryPath, bytes);
        return APIUtil.apiCreateLibrary(libraryPath.toAbsolutePath().toString());
    }

    public static byte[] readResource(String resourceName, String label) {
        try (InputStream input = NativeLibraries.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled " + label + " " + resourceName);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read bundled " + label, exception);
        }
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
