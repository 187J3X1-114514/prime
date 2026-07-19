package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class StagingArenaTest {
    @Test
    void indivisibleUploadsRoundToWholePages() {
        assertEquals(StagingArena.PAGE_SIZE, StagingArena.allocationCapacity(0L));
        assertEquals(StagingArena.PAGE_SIZE, StagingArena.allocationCapacity(StagingArena.PAGE_SIZE));
        assertEquals(
                StagingArena.PAGE_SIZE * 2L,
                StagingArena.allocationCapacity(StagingArena.PAGE_SIZE + 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> StagingArena.allocationCapacity(Long.MAX_VALUE));
    }
}
