package dev.prime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class PrimeConfigTest {
    @Test
    void persistedEvAcceptsOnlyExactQuarterStopsInRange() {
        assertEquals(5, PrimeConfig.parseEvQuarterSteps("1.25"));
        assertEquals(-32, PrimeConfig.parseEvQuarterSteps("-8"));
        assertEquals(32, PrimeConfig.parseEvQuarterSteps("8"));
        assertEquals("1.25", PrimeConfig.formatEv(5));
        assertEquals("0", PrimeConfig.formatEv(0));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfig.parseEvQuarterSteps("0.1"));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfig.parseEvQuarterSteps("8.25"));
    }

    @Test
    void persistedOklabOverexposureAcceptsOnlyExactThirtySeconds() {
        assertEquals(32, PrimeConfig.parseOverexposureSteps("1"));
        assertEquals(33, PrimeConfig.parseOverexposureSteps("1.03125"));
        assertEquals(64, PrimeConfig.parseOverexposureSteps("2"));
        assertEquals("1.03125", PrimeConfig.formatOverexposure(33));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfig.parseOverexposureSteps("1.03"));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfig.parseOverexposureSteps("2.03125"));
    }
}
