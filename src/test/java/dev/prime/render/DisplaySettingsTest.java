package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class DisplaySettingsTest {
    @Test
    void finalExposureUsesQuarterEvStepsAcrossTheUserRange() {
        assertEquals(1.0F, DisplaySettings.finalExposureMultiplier(0));
        assertEquals(2.0F, DisplaySettings.finalExposureMultiplier(4));
        assertEquals(0.5F, DisplaySettings.finalExposureMultiplier(-4));
        assertEquals(
                (float) Math.pow(2.0, 0.25),
                DisplaySettings.finalExposureMultiplier(1));
        assertEquals(256.0F, DisplaySettings.finalExposureMultiplier(32));
        assertEquals(1.0F / 256.0F, DisplaySettings.finalExposureMultiplier(-32));
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplaySettings.finalExposureMultiplier(33));
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplaySettings.finalExposureMultiplier(-33));
    }

    @Test
    void overexposureUsesExactHundredthStepsAcrossTheUserRange() {
        assertEquals(1.0F, DisplaySettings.overexposure(100));
        assertEquals(1.01F, DisplaySettings.overexposure(101));
        assertEquals(2.0F, DisplaySettings.overexposure(200));
        assertEquals(100, DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS);
        assertEquals(1.0F, DisplaySettings.overexposure(
                DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS));
        assertThrows(IllegalArgumentException.class, () -> DisplaySettings.overexposure(99));
        assertThrows(IllegalArgumentException.class, () -> DisplaySettings.overexposure(201));
    }

    @Test
    void curveExponentUsesHundredthStepsAcrossTheSupportedCurveFamily() {
        assertEquals(0.5F, DisplaySettings.curveExponent(50));
        assertEquals(0.75F, DisplaySettings.curveExponent(75));
        assertEquals(1.0F, DisplaySettings.curveExponent(100));
        assertEquals(75, DisplaySettings.DEFAULT_CURVE_EXPONENT_STEPS);
        assertThrows(IllegalArgumentException.class, () -> DisplaySettings.curveExponent(49));
        assertThrows(IllegalArgumentException.class, () -> DisplaySettings.curveExponent(101));
        float scale = 1.0F / (1.0F - 0.18F);
        assertEquals(
                (scale * scale - 1.0F) / 0.18F,
                DisplaySettings.curveCoefficient(100, 50),
                2.0e-6F);
    }

    @Test
    void autoExposureCompensationCoversDisabledThroughFullMetering() {
        assertEquals(0.0F, DisplaySettings.autoExposureCompensation(0));
        assertEquals(0.5F, DisplaySettings.autoExposureCompensation(50));
        assertEquals(1.0F, DisplaySettings.autoExposureCompensation(100));
        assertEquals(50, DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS);
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplaySettings.autoExposureCompensation(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplaySettings.autoExposureCompensation(101));
    }
}
