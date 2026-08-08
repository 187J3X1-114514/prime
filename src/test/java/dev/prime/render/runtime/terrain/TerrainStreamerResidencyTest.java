package dev.prime.render.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.terrain.CompiledCluster;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

final class TerrainStreamerResidencyTest {
    @Test
    void staticWindowNeverEvictsDynamicScene() {
        assertFalse(TerrainStreamer.isOutsideStaticWindow(
                CompiledCluster.DYNAMIC_KEY, new LongOpenHashSet()));
    }

    @Test
    void staticWindowEvictsOnlyTerrainOutsideItsReplacement() {
        LongOpenHashSet window = new LongOpenHashSet(new long[] {7L});

        assertFalse(TerrainStreamer.isOutsideStaticWindow(7L, window));
        assertTrue(TerrainStreamer.isOutsideStaticWindow(8L, window));
    }
}
