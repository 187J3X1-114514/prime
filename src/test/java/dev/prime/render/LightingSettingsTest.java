package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LightingSettingsTest {
    @Test
    void quarterEvStepsConvertToExactPowerOfTwoMultipliers() {
        assertEquals(-32, LightingSettings.MINIMUM_QUARTER_STEPS);
        assertEquals(32, LightingSettings.MAXIMUM_QUARTER_STEPS);
        assertEquals(1.0F, LightingSettings.linearMultiplier(0));
        assertEquals(2.0F, LightingSettings.linearMultiplier(4));
        assertEquals(0.5F, LightingSettings.linearMultiplier(-4));
        assertEquals(256.0F, LightingSettings.linearMultiplier(32));
        assertEquals(1.0F / 256.0F, LightingSettings.linearMultiplier(-32));
        assertEquals((float) Math.pow(2.0, 0.25), LightingSettings.linearMultiplier(1));
        assertThrows(IllegalArgumentException.class,
                () -> LightingSettings.linearMultiplier(
                        LightingSettings.MAXIMUM_QUARTER_STEPS + 1));
    }

    @Test
    void changingEitherControlAdvancesTheSharedLightingRevision() {
        int originalSun = LightingSettings.sunQuarterSteps();
        int originalBlock = LightingSettings.blockLightQuarterSteps();
        try {
            long originalRevision = LightingSettings.snapshot().revision();
            int changedSun = originalSun == LightingSettings.MAXIMUM_QUARTER_STEPS
                    ? originalSun - 1
                    : originalSun + 1;
            LightingSettings.setSunQuarterSteps(changedSun);
            long sunRevision = LightingSettings.snapshot().revision();
            assertTrue(sunRevision > originalRevision);
            LightingSettings.setSunQuarterSteps(changedSun);
            assertEquals(sunRevision, LightingSettings.snapshot().revision());

            int changedBlock = originalBlock == LightingSettings.MAXIMUM_QUARTER_STEPS
                    ? originalBlock - 1
                    : originalBlock + 1;
            LightingSettings.setBlockLightQuarterSteps(changedBlock);
            assertTrue(LightingSettings.snapshot().revision() > sunRevision);
        } finally {
            LightingSettings.setSunQuarterSteps(originalSun);
            LightingSettings.setBlockLightQuarterSteps(originalBlock);
        }
    }
}
