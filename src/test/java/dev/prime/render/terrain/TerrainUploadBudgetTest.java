package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TerrainUploadBudgetTest {
    @Test
    void stagingBudgetIncludesTheAlignmentBeforeSectionLights() {
        // A triangle contributes 36 position bytes and 36 primitive bytes. Its 16-byte-aligned
        // light record starts at 80, not at the raw payload sum of 72.
        assertEquals(96L, TerrainStreamer.stagingEndOffset(0L, 36L, 36L, 16L));
        assertEquals(168L, TerrainStreamer.stagingEndOffset(96L, 36L, 36L, 0L));
    }
}
