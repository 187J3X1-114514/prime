package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AgxHsvOutputTest {
    private static final double INPUT_PIVOT = 10.0 / 16.5;
    private static final double OUTPUT_PIVOT = 0.46135613;
    private static final double PIVOT_SLOPE = 2.4606366;
    private static final double SHOULDER_POWER = 5.2;

    @Test
    void sdrRetainsTheCalibratedAgxHsvCurve() {
        AgxHsvOutput.Parameters parameters = AgxHsvOutput.parameters(1.0F);

        assertEquals(1.0F, parameters.maximumLogCoordinate());
        assertEquals(1.0F, parameters.outputPeak());
        assertEquals(2_568.749_8F, parameters.shoulderCoefficient(), 0.02F);
    }

    @Test
    void hdrExtendsTheSameShoulderThroughTheRequestedPeak() {
        AgxHsvOutput.Parameters parameters = AgxHsvOutput.parameters(4.0F);

        assertEquals(1.824_796_3F, parameters.outputPeak(), 2.0E-7F);
        assertEquals(1.603_218_1F, parameters.maximumLogCoordinate(), 2.0E-7F);
        double distance = parameters.maximumLogCoordinate() - INPUT_PIVOT;
        double mappedPeak = OUTPUT_PIVOT
                + PIVOT_SLOPE * distance
                        * Math.pow(
                                1.0 + parameters.shoulderCoefficient()
                                        * Math.pow(distance, SHOULDER_POWER),
                                -1.0 / SHOULDER_POWER);
        assertEquals(parameters.outputPeak(), mappedPeak, 2.0E-6);
    }

    @Test
    void headroomIsFiniteAndClampedToTheSupportedContract() {
        assertEquals(
                AgxHsvOutput.parameters(AgxHsvOutput.MINIMUM_HEADROOM),
                AgxHsvOutput.parameters(-10.0F));
        assertEquals(
                AgxHsvOutput.parameters(AgxHsvOutput.MAXIMUM_HEADROOM),
                AgxHsvOutput.parameters(AgxHsvOutput.MAXIMUM_HEADROOM + 1.0F));
        AgxHsvOutput.Parameters maximum =
                AgxHsvOutput.parameters(AgxHsvOutput.MAXIMUM_HEADROOM);
        assertTrue(Float.isFinite(maximum.maximumLogCoordinate()));
        assertTrue(Float.isFinite(maximum.outputPeak()));
        assertTrue(Float.isFinite(maximum.shoulderCoefficient()));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgxHsvOutput.parameters(Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgxHsvOutput.parameters(Float.POSITIVE_INFINITY));
    }
}
