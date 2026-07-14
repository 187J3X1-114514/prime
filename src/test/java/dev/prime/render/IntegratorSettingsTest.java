package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IntegratorSettingsTest {
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
    void sampleStreamIsStableDimensionedAndStrictlyUnitRange() {
        float first = IntegratorSettings.sample(17, 29, 3, 5, 7);
        assertEquals(first, IntegratorSettings.sample(17, 29, 3, 5, 7));
        assertNotEquals(first, IntegratorSettings.sample(17, 29, 3, 5, 8));
        for (int dimension = 0; dimension < 10_000; dimension++) {
            float sample = IntegratorSettings.sample(17, 29, 3, 5, dimension);
            assertTrue(sample >= 0.0F && sample < 1.0F);
        }
    }

    @Test
    void diffuseNeeAndBsdfSamplingConvergeToConstantEnvironmentReference() {
        float reflectance = 0.6F;
        float environment = 0.2F;
        float lightPdf = IntegratorSettings.environmentPdf(1.0F);
        double total = 0.0;
        int sampleCount = 200_000;
        for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
            float lightCosine = IntegratorSettings.sample(0, 0, sampleIndex, 1, 0);
            float lightBsdfPdf = IntegratorSettings.diffusePdf(lightCosine);
            float lightWeight = IntegratorSettings.powerHeuristic(lightPdf, lightBsdfPdf);
            double direct = environment * reflectance / Math.PI * lightCosine * lightWeight / lightPdf;

            float bsdfCosine = (float) Math.sqrt(
                    1.0F - IntegratorSettings.sample(0, 0, sampleIndex, 1, 1));
            float bsdfPdf = IntegratorSettings.diffusePdf(bsdfCosine);
            float bsdfWeight = IntegratorSettings.powerHeuristic(bsdfPdf, lightPdf);
            double escaped = environment * reflectance * bsdfWeight;
            total += direct + escaped;
        }
        assertEquals(environment * reflectance, total / sampleCount, 0.002);
    }
}
