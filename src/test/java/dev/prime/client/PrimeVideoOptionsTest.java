package dev.prime.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class PrimeVideoOptionsTest {
    @Test
    void overexposureLabelMatchesHundredthStepValue() {
        assertEquals("1.00×", PrimeVideoOptions.formatOverexposure(100));
        assertEquals("1.01×", PrimeVideoOptions.formatOverexposure(101));
        assertEquals("2.00×", PrimeVideoOptions.formatOverexposure(200));
    }
}
