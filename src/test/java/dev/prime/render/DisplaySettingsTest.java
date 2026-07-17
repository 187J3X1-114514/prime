package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class DisplaySettingsTest {
    @Test
    void overexposureUsesExactThirtySecondStepsAcrossTheUserRange() {
        assertEquals(1.0F, DisplaySettings.overexposure(32));
        assertEquals(1.03125F, DisplaySettings.overexposure(33));
        assertEquals(2.0F, DisplaySettings.overexposure(64));
        assertEquals(37, DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS);
        assertEquals(1.15625F, DisplaySettings.overexposure(
                DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS));
        assertThrows(IllegalArgumentException.class, () -> DisplaySettings.overexposure(31));
        assertThrows(IllegalArgumentException.class, () -> DisplaySettings.overexposure(65));
    }
}
