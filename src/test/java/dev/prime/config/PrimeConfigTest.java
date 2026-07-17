package dev.prime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class PrimeConfigTest {
    @Test
    void persistedEvAcceptsOnlyExactQuarterStopsInRange() {
        assertEquals(5, PrimeConfig.parseEvQuarterSteps("1.25"));
        assertEquals(-16, PrimeConfig.parseEvQuarterSteps("-4"));
        assertEquals("1.25", PrimeConfig.formatEv(5));
        assertEquals("0", PrimeConfig.formatEv(0));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfig.parseEvQuarterSteps("0.1"));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfig.parseEvQuarterSteps("4.25"));
    }
}
