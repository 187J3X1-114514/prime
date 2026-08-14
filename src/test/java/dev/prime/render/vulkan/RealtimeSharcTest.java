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
    void sparseUpdateCyclesEveryFiveByFivePhase() {
        for (int frame = 0; frame < 50; frame++) {
            assertEquals(frame % 25, RealtimeSharc.updatePhase(frame));
        }
        assertEquals(
                Integer.remainderUnsigned(-1, 25),
                RealtimeSharc.updatePhase(-1));
    }

    @Test
    void integratedTrainingStoresFourCompactEventsPerCarrierPath() {
        assertEquals(4, RealtimeSharc.TRAINING_MAX_EVENTS);
        assertEquals(80, RealtimeSharc.TRAINING_EVENT_BYTES);
        assertEquals(26_542_080L, RealtimeSharc.trainingBytes(1920, 1080));
        assertEquals(106_168_320L, RealtimeSharc.trainingBytes(3840, 2160));
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
