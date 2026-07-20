package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TerrainTemporalHistoryTest {
    @Test
    void onlyRenderOriginChangesInvalidateTheCurrentWorldHistory() {
        assertFalse(TerrainScene.invalidatesTemporalHistory(false));
        assertTrue(TerrainScene.invalidatesTemporalHistory(true));
    }
}
