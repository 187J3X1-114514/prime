package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.shader.ShaderAbi;
import org.junit.jupiter.api.Test;

final class IntegratorSettingsTest {
    @Test
    void pathControlKeepsCameraMediumSeparateFromJitterAndBounceFields() {
        int dry = IntegratorSettings.packPathControl(256, 18, false);
        int submerged = IntegratorSettings.packPathControl(256, 18, true);
        assertEquals(256, dry & 0xffff);
        assertEquals(18, (dry >>> 16) & ShaderAbi.PATH_JITTER_PHASE_MASK);
        assertEquals(0, dry & ShaderAbi.PATH_CAMERA_IN_WATER_MASK);
        assertEquals(ShaderAbi.PATH_CAMERA_IN_WATER_MASK,
                submerged & ShaderAbi.PATH_CAMERA_IN_WATER_MASK);
        assertThrows(IllegalArgumentException.class,
                () -> IntegratorSettings.packPathControl(256, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> IntegratorSettings.packPathControl(256, 0x8000, false));
    }

    @Test
    void reciprocalMisWeightsFormACompletePartition() {
        float forward = IntegratorSettings.powerHeuristic(0.3F, 0.7F);
        float reverse = IntegratorSettings.powerHeuristic(0.7F, 0.3F);
        assertEquals(1.0F, forward + reverse, 1.0e-6F);
    }

    @Test
    void rouletteCompensationPreservesExpectedThroughput() {
        float throughput = 0.2F;
        float survival = IntegratorSettings.rouletteSurvival(throughput);
        assertEquals(throughput, survival * (throughput / survival), 1.0e-6F);
        assertEquals(0.05F, IntegratorSettings.rouletteSurvival(0.001F));
        assertEquals(0.95F, IntegratorSettings.rouletteSurvival(10.0F));
    }

    @Test
    void onlineMeanMatchesBatchMean() {
        float mean = IntegratorSettings.updateMean(0.0F, 1.0F, 0);
        mean = IntegratorSettings.updateMean(mean, 2.0F, 1);
        mean = IntegratorSettings.updateMean(mean, 6.0F, 2);
        assertEquals(3.0F, mean, 1.0e-6F);
    }

    @Test
    void sobolStreamIsStableSeparatedByEffectAndStrictlyUnitRange() {
        float[] first = IntegratorSettings.sobolSample2D(17, 29, 3, 5, 7, 1, 0);
        assertArrayEquals(first, IntegratorSettings.sobolSample2D(17, 29, 3, 5, 7, 1, 0));
        assertNotEquals(first[0], IntegratorSettings.sobolSample2D(17, 29, 4, 5, 7, 1, 0)[0]);
        assertNotEquals(first[0], IntegratorSettings.sobolSample2D(17, 29, 3, 5, 7, 2, 0)[0]);
        for (int sampleIndex = 0; sampleIndex < 10_000; sampleIndex++) {
            float[] sample = IntegratorSettings.sobolSample2D(
                    17, 29, sampleIndex, 5, 7, 1, 0);
            assertTrue(sample[0] >= 0.0F && sample[0] < 1.0F);
            assertTrue(sample[1] >= 0.0F && sample[1] < 1.0F);
        }
    }

    @Test
    void sobolPrefixStratifiesBothAxes() {
        int[] xBins = new int[16];
        int[] yBins = new int[16];
        for (int sampleIndex = 0; sampleIndex < 256; sampleIndex++) {
            float[] sample = IntegratorSettings.sobolSample2D(
                    17, 29, sampleIndex, 5, 7, 1, 0);
            xBins[(int) (sample[0] * 16.0F)]++;
            yBins[(int) (sample[1] * 16.0F)]++;
        }
        for (int bin = 0; bin < 16; bin++) {
            assertEquals(16, xBins[bin]);
            assertEquals(16, yBins[bin]);
        }
    }

    @Test
    void diffusePdfIsDefinedOnlyOnTheVisibleHemisphere() {
        assertEquals(1.0F / (float) Math.PI, IntegratorSettings.diffusePdf(1.0F), 1.0E-7F);
        assertEquals(0.0F, IntegratorSettings.diffusePdf(0.0F));
        assertEquals(0.0F, IntegratorSettings.diffusePdf(-1.0F));
    }
}
