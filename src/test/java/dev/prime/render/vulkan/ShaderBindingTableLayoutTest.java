package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ShaderBindingTableLayoutTest {
    @Test
    void alignsEveryRegionEvenWhenTheBufferAddressIsNotBaseAligned() {
        long bufferAddress = 0x1008L;
        ShaderBindingTableLayout layout = ShaderBindingTableLayout.create(32, 32, 64, 2, bufferAddress);
        assertEquals(32L, layout.recordStride());
        assertEquals(56L, layout.raygenOffset());
        assertEquals(120L, layout.missOffset());
        assertEquals(184L, layout.hitOffset());
        assertEquals(248L, layout.totalSize());
        assertEquals(0L, (bufferAddress + layout.raygenOffset()) % 64L);
        assertEquals(0L, (bufferAddress + layout.missOffset()) % 64L);
        assertEquals(0L, (bufferAddress + layout.hitOffset()) % 64L);
        assertTrue(layout.totalSize() <= ShaderBindingTableLayout.minimumBufferSize(32, 32, 64, 2));
    }

    @Test
    void rejectsNonPowerOfTwoAlignments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShaderBindingTableLayout.create(32, 24, 64, 2, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> ShaderBindingTableLayout.create(32, 32, 48, 2, 0L));
    }
}
