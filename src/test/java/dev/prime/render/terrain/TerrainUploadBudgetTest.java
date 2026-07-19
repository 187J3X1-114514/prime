package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TerrainUploadBudgetTest {
    @Test
    void stagingBudgetIncludesTheAlignmentBeforeSectionLights() {
        // A triangle contributes 36 position bytes and 32 primitive bytes. Its 16-byte-aligned
        // 12-byte light record starts at 80, not at the raw payload sum of 68.
        assertEquals(
                184L,
                TerrainStreamer.stagingEndOffset(
                        0L, 36L, 32L, 12L, 8L, 64L, 8L));
        assertEquals(
                252L,
                TerrainStreamer.stagingEndOffset(
                        184L, 36L, 32L, 0L, 0L, 0L, 0L));
    }
}
