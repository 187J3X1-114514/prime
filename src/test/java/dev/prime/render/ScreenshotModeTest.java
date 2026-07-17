package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class ScreenshotModeTest {
    @AfterEach
    void resetGlobalState() {
        ScreenshotMode.reset();
    }

    @Test
    void requestDoesNotPublishActiveStateBeforeRendererAcceptsIt() {
        ScreenshotMode.request(true);

        assertTrue(ScreenshotMode.requested());
        assertFalse(ScreenshotMode.active());

        ScreenshotMode.activate();
        assertTrue(ScreenshotMode.active());

        ScreenshotMode.request(false);
        assertTrue(ScreenshotMode.active());
        ScreenshotMode.deactivate();
        assertFalse(ScreenshotMode.active());
    }
}
