package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SharcDiagnosticsSnapshotTest {
    @Test
    void reportsLookupAndPathTerminationAsDifferentEffectivenessRates() {
        var snapshot = new SharcDiagnosticsSnapshot(
                true,
                4L,
                0.2,
                0.1,
                1.5,
                3L,
                2.4,
                100L,
                10L,
                5L,
                25L,
                60L,
                45L,
                256);

        assertEquals(0.75, snapshot.lookupHitRate());
        assertEquals(0.45, snapshot.terminationRate());
        assertEquals(1.8, snapshot.totalMilliseconds(), 1.0e-12);
        assertEquals(0.6, snapshot.estimatedNetSavingMilliseconds(), 1.0e-12);
        assertEquals(0.25, snapshot.estimatedNetSavingRate(), 1.0e-12);
        assertEquals(256, snapshot.samplingPeriod());
    }

    @Test
    void emptySamplesDoNotInventAHitRate() {
        var snapshot = new SharcDiagnosticsSnapshot(
                false,
                0L,
                0.0,
                0.0,
                0.0,
                0L,
                0.0,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                256);

        assertTrue(Double.isNaN(snapshot.lookupHitRate()));
        assertTrue(Double.isNaN(snapshot.terminationRate()));
        assertTrue(Double.isNaN(snapshot.estimatedNetSavingMilliseconds()));
    }
}
