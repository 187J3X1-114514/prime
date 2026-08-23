package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

final class SessionControllerTest {
    @Test
    void escapeOnlyCancelsAnActiveOrRequestedSession() {
        SessionController controller = new SessionController();
        controller.requestScreenshot(true);

        controller.update(new SessionController.KeyState(true), false);

        assertFalse(controller.controls().screenshotRequested());
    }
}
