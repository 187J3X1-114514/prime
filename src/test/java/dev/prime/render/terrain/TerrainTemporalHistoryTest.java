package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.vulkan.terrain.TerrainScene;
import org.junit.jupiter.api.Test;

final class TerrainTemporalHistoryTest {
    @Test
    void onlyAnUnrelatedWorldAdvancesTemporalIdentity() {
        long revision = 17L;

        assertEquals(
                revision,
                TerrainScene.nextTemporalRevision(
                        revision,
                        TerrainScene.TemporalContinuity.RELATED_UPDATE));
        assertEquals(
                revision + 1L,
                TerrainScene.nextTemporalRevision(
                        revision,
                        TerrainScene.TemporalContinuity.UNRELATED_WORLD));
    }
}
