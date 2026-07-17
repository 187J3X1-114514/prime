package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TerrainUploadBudgetTest {
    @Test
    void stagingBudgetIncludesTheAlignmentBeforeSectionLights() {
        // A triangle contributes 36 position bytes and 32 primitive bytes. Its 16-byte-aligned
        // light record starts at 80, not at the raw payload sum of 68.
        assertEquals(96L, TerrainStreamer.stagingEndOffset(0L, 36L, 32L, 16L));
        assertEquals(164L, TerrainStreamer.stagingEndOffset(96L, 36L, 32L, 0L));
    }
}
