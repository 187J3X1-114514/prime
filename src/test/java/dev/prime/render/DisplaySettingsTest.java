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
    void overexposureUsesExactThirtySecondStepsAcrossTheUserRange() {
        assertEquals(1.0F, DisplaySettings.overexposure(32));
        assertEquals(1.03125F, DisplaySettings.overexposure(33));
        assertEquals(2.0F, DisplaySettings.overexposure(64));
        assertEquals(35, DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS);
        assertEquals(1.09375F, DisplaySettings.overexposure(
                DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS));
        assertThrows(IllegalArgumentException.class, () -> DisplaySettings.overexposure(31));
        assertThrows(IllegalArgumentException.class, () -> DisplaySettings.overexposure(65));
    }
}
