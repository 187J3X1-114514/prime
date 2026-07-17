package dev.prime.render.vulkan.fsr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

final class FsrNativeTest {
    @Test
    void platformDetectionAcceptsOnlyWindowsX64() {
        assertTrue(FsrNative.isSupportedPlatform("Windows 11", "amd64"));
        assertTrue(FsrNative.isSupportedPlatform("Windows 10", "x86_64"));
        assertFalse(FsrNative.isSupportedPlatform("Windows 11", "aarch64"));
        assertFalse(FsrNative.isSupportedPlatform("Linux", "amd64"));
    }

    @Test
    void bundledLibraryLoadsAndExportsTheFsrApi() {
        assumeTrue(
                FsrNative.isSupportedPlatform(),
                "bundled FidelityFX loading test requires Windows x86-64");
        assertDoesNotThrow(FsrNative::verifyLibrary);
    }
}
