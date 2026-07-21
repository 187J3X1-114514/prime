package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeRuntimeFilesTest {
    @Test
    void publishRepairsAStaleRuntimeAndRemainsIdempotent(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("runtime.dll");
        byte[] expected = {1, 2, 3, 4};
        Files.write(target, new byte[] {9, 8});

        NativeRuntimeFiles.publish(target, expected);
        NativeRuntimeFiles.publish(target, expected);

        assertArrayEquals(expected, Files.readAllBytes(target));
    }

    @Test
    void contentAddressPreservesPayloadBoundaries() {
        assertNotEquals(
                NativeRuntimeFiles.directory("prime-test", new byte[] {1}, new byte[] {2, 3}),
                NativeRuntimeFiles.directory("prime-test", new byte[] {1, 2}, new byte[] {3}));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeRuntimeFiles.directory("../outside", new byte[] {1}));
    }
}
