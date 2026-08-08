package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SessionControllerTest {
    @Test
    void shortcutsAreRisingEdgeTriggered() {
        SessionController controller = new SessionController();
        SessionController.KeyState pressed = new SessionController.KeyState(false, true, true);

        controller.update(pressed, false);
        var firstControls = controller.controls();
        controller.update(pressed, false);

        assertTrue(firstControls.rrDebugFullscreen());
        assertTrue(controller.controls().rrDebugFullscreen());
    }

    @Test
    void escapeOnlyCancelsAnActiveOrRequestedSession() {
        SessionController controller = new SessionController();
        controller.requestScreenshot(true);

        controller.update(new SessionController.KeyState(true, false, false), false);

        assertFalse(controller.controls().screenshotRequested());
    }
}
