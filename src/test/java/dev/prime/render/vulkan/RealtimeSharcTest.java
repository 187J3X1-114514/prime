package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.LightingSettings;
import org.junit.jupiter.api.Test;

final class RealtimeSharcTest {
    @Test
    void cacheUsesTheFixedFourMillionEntryLayout() {
        assertEquals(1 << 22, RealtimeSharc.CAPACITY);
        assertEquals(32L << 20, RealtimeSharc.HASH_BYTES);
        assertEquals(128L << 20, RealtimeSharc.ACCUMULATION_BYTES);
        assertEquals(96L << 20, RealtimeSharc.RESOLVED_BYTES);
        assertEquals(256L << 20, RealtimeSharc.CACHE_BYTES);
    }

    @Test
    void threeByThreeTrainingRotatesEachSweepAwayFromReconstructionJitter() {
        for (int sweep = 0; sweep < 18; sweep++) {
            boolean[] visited = new boolean[9];
            for (int local = 0; local < 9; local++) {
                int phase = RealtimeSharc.updatePhase(sweep * 9 + local);
                assertFalse(visited[phase]);
                visited[phase] = true;
            }
            for (boolean selected : visited) {
                assertTrue(selected);
            }
        }
        assertEquals(0, RealtimeSharc.updatePhase(0));
        assertEquals(1, RealtimeSharc.updatePhase(9));
        assertEquals(8, RealtimeSharc.updatePhase(72));
        assertEquals(0, RealtimeSharc.updatePhase(81));
        for (int frame = 0; frame < 8; frame++) {
            assertTrue(
                    RealtimeSharc.updatePhase(frame)
                            != RealtimeSharc.updatePhase(frame + 64));
            assertTrue(
                    RealtimeSharc.updatePhase(frame)
                            != RealtimeSharc.updatePhase(frame + 72));
        }
        long unsignedMaximum = 0xffff_ffffL;
        assertEquals(
                (int) ((unsignedMaximum % 9 + (unsignedMaximum / 9) % 9) % 9),
                RealtimeSharc.updatePhase(-1));
    }

    @Test
    void integratedTrainingStoresSixCollapsedAnchorsPerCarrierPath() {
        assertEquals(6, RealtimeSharc.TRAINING_ANCHOR_COUNT);
        assertEquals(472, RealtimeSharc.TRAINING_RECORD_BYTES);
        assertEquals(108_748_800L, RealtimeSharc.trainingBytes(1920, 1080));
        assertEquals(434_995_200L, RealtimeSharc.trainingBytes(3840, 2160));
        assertThrows(
                IllegalArgumentException.class,
                () -> RealtimeSharc.trainingBytes(0, 1080));
    }

    @Test
    void radianceScaleTracksTheBrightestConfiguredEmitterFamily() {
        assertEquals(
                1000.0F,
                RealtimeSharc.radianceScale(new LightingSettings.Snapshot(0, 0, 0, 0L)));
        assertEquals(
                250.0F,
                RealtimeSharc.radianceScale(new LightingSettings.Snapshot(8, 0, 0, 0L)));
        assertEquals(
                62.5F,
                RealtimeSharc.radianceScale(new LightingSettings.Snapshot(0, 16, 0, 0L)));
    }

    @Test
    void cacheIdentityInvalidatesOnlyContentAndCoordinateChanges() {
        var material = new dev.prime.render.MaterialSettings.Snapshot(90, true, 4L);
        var accepted = new RealtimeSharc.Accepted(
                1.0F, 2.0F, 3.0F, 16, 32, 48, 7L, 9L, material, 11);
        assertFalse(RealtimeSharc.requiresClear(
                accepted, 16, 32, 48, 7L, 9L, material));
        assertTrue(RealtimeSharc.requiresClear(
                accepted, 0, 32, 48, 7L, 9L, material));
        assertTrue(RealtimeSharc.requiresClear(
                accepted, 16, 32, 48, 8L, 9L, material));
        assertTrue(RealtimeSharc.requiresClear(
                accepted, 16, 32, 48, 7L, 10L, material));
        assertTrue(RealtimeSharc.requiresClear(
                accepted,
                16,
                32,
                48,
                7L,
                9L,
                new dev.prime.render.MaterialSettings.Snapshot(80, true, 5L)));
    }
}
