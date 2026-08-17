package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class HdrOutputTest {
    @AfterEach
    void resetGlobalState() {
        HdrOutput.setRequested(false);
        HdrOutput.updateCapability(false, AgxHsvOutput.MINIMUM_HEADROOM);
    }

    @Test
    void hdrRequiresBothAUserRequestAndAUsableDisplay() {
        HdrOutput.setRequested(true);
        assertFalse(HdrOutput.capability().supported());
        assertEquals(1.0F, HdrOutput.activeHeadroom());

        HdrOutput.updateCapability(true, 4.0F);
        assertTrue(HdrOutput.capability().supported());
        assertEquals(4.0F, HdrOutput.activeHeadroom());

        HdrOutput.setRequested(false);
        assertEquals(1.0F, HdrOutput.activeHeadroom());
    }

    @Test
    void capabilityClampsHeadroomAndRejectsNonFiniteMeasurements() {
        HdrOutput.updateCapability(true, 100.0F);
        assertEquals(AgxHsvOutput.MAXIMUM_HEADROOM, HdrOutput.capability().headroom());
        assertThrows(
                IllegalArgumentException.class,
                () -> HdrOutput.updateCapability(true, Float.NaN));
    }
}
