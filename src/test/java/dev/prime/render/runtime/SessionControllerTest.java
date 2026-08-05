package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SessionControllerTest {
    @Test
    void shortcutsAreRisingEdgeTriggered() {
        SessionController controller = new SessionController();
        SessionController.KeyState pressed = new SessionController.KeyState(false, true, true, true);

        SessionController.Actions first = controller.update(pressed, false);
        var firstControls = controller.controls();
        SessionController.Actions held = controller.update(pressed, false);

        assertTrue(first.replayRequested());
        assertFalse(held.replayRequested());
        assertTrue(firstControls.rrDebugFullscreen());
        assertTrue(controller.controls().rrDebugFullscreen());
    }

    @Test
    void escapeOnlyCancelsAnActiveOrRequestedSession() {
        SessionController controller = new SessionController();
        controller.requestScreenshot(true);

        controller.update(new SessionController.KeyState(true, false, false, false), false);

        assertFalse(controller.controls().screenshotRequested());
    }
}
