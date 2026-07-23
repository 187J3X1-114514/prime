package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.config.PrimeSettings;
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
        assertEquals(1.0F, LightingSettings.starLinearMultiplier(0));
        assertEquals(
                256.0F,
                LightingSettings.starLinearMultiplier(32));
        assertThrows(IllegalArgumentException.class,
                () -> LightingSettings.linearMultiplier(
                        LightingSettings.MAXIMUM_QUARTER_STEPS + 1));
        assertThrows(IllegalArgumentException.class,
                () -> LightingSettings.starLinearMultiplier(33));
    }

    @Test
    void changingEitherControlAdvancesTheSharedLightingRevision() {
        PrimeSettings defaults = PrimeSettings.defaults();
        PrimeSettings sun = defaults.withSunQuarterSteps(1);
        assertTrue(sun.lightingRevision() > defaults.lightingRevision());
        assertEquals(sun, sun.withSunQuarterSteps(1));

        PrimeSettings stars = sun.withStarQuarterSteps(2);
        assertTrue(stars.lightingRevision() > sun.lightingRevision());
        PrimeSettings block = stars.withBlockLightQuarterSteps(-1);
        assertTrue(block.lightingRevision() > stars.lightingRevision());
        assertEquals(1, block.lighting().sunQuarterSteps());
        assertEquals(2, block.lighting().starQuarterSteps());
        assertEquals(-1, block.lighting().blockLightQuarterSteps());
    }
}
