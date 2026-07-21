package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NrdNumericalStabilityTest {
    private static final double FP16_MAX = 65_504.0;

    @Test
    void thinWallSeriesUsesTheExactFiniteLimitAtLosslessGrazingIncidence() {
        double oldTransmission = oldThinWallTransmission(1.0, 1.0);
        assertTrue(Double.isNaN(oldTransmission), "the former 0 / 0 regression must stay observable");

        Energy grazing = stableThinWallEnergy(1.0, 1.0);
        assertEquals(1.0, grazing.reflection, 0.0);
        assertEquals(0.0, grazing.transmission, 0.0);
        assertTrue(Double.isFinite(grazing.reflection));
        assertTrue(Double.isFinite(grazing.transmission));

        double[] reflections = {0.0, 0.04, 0.5, 0.9, 0.999_999};
        double[] absorptions = {0.1, 0.75, 0.999, 1.0};
        for (double reflection : reflections) {
            for (double absorption : absorptions) {
                double oldValue = oldThinWallTransmission(reflection, absorption);
                double stableValue = stableThinWallEnergy(reflection, absorption).transmission;
                assertEquals(oldValue, stableValue, 1.0e-10,
                        "the guard must preserve RoboCute's result away from the singularity");
            }
        }
    }

    @Test
    void finiteBoundaryLeavesRepresentableDistancesUntouched() {
        assertEquals(0.0, sanitizeHitDistance(0.0), 0.0);
        assertEquals(123.5, sanitizeHitDistance(123.5), 0.0);
        assertEquals(FP16_MAX, sanitizeHitDistance(FP16_MAX), 0.0);
        assertEquals(FP16_MAX, sanitizeHitDistance(Double.POSITIVE_INFINITY), 0.0);
        assertEquals(FP16_MAX, sanitizeHitDistance(Double.NaN), 0.0);
        assertEquals(FP16_MAX, sanitizeHitDistance(1.0e9), 0.0);
    }

    private static double oldThinWallTransmission(double reflection, double absorption) {
        double transmission = 1.0 - reflection;
        return transmission * transmission * absorption
                / (1.0 - reflection * reflection * absorption * absorption);
    }

    private static Energy stableThinWallEnergy(double reflection, double absorption) {
        double transmission = 1.0 - reflection;
        double roundTrip = reflection * absorption;
        double denominator = 1.0 - roundTrip * roundTrip;
        double inverseSeries = denominator > 0.0 && Double.isFinite(denominator)
                ? 1.0 / denominator
                : 0.0;
        if (!Double.isFinite(inverseSeries)) {
            inverseSeries = 0.0;
        }
        return new Energy(
                reflection * (1.0 + transmission * transmission
                        * absorption * absorption * inverseSeries),
                transmission * transmission * absorption * inverseSeries);
    }

    private static double sanitizeHitDistance(double value) {
        return Double.isFinite(value) ? Math.clamp(value, 0.0, FP16_MAX) : FP16_MAX;
    }

    private record Energy(double reflection, double transmission) {}
}
