package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class VulkanBufferTest {
    @Test
    void mappedRangesRejectOverflowWithoutTouchingNativeMemory() {
        VulkanBuffer buffer = new VulkanBuffer(1L, 2L, 3L, 4L, 5L, 16L);
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> buffer.put(Long.MAX_VALUE, ByteBuffer.allocateDirect(1)));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> buffer.put(15L, 1L, 2L));
    }

    @Test
    void hostOperationsRejectDeviceOnlyBuffers() {
        VulkanBuffer buffer = new VulkanBuffer(1L, 2L, 3L, 4L, 0L, 16L);
        assertThrows(IllegalStateException.class, () -> buffer.flush(0L, 16L));
        assertThrows(IllegalStateException.class, () -> buffer.invalidate(0L, 16L));
    }
}
