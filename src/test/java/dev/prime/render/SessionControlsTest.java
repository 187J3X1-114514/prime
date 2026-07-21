package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import org.junit.jupiter.api.Test;

final class SessionControlsTest {
    @Test
    void transitionsPreserveAnImmutableCoherentSnapshot() {
        SessionControls defaults = SessionControls.defaults();
        SessionControls changed = defaults
                .withScreenshotRequested(true)
                .withNrdDebugView(NrdDiagnostics.Mode.OPAQUE)
                .withFsrDebugView(FsrDebugView.OVERVIEW)
                .withRrDebugView(DlssRrDebugView.MOTION)
                .withRrDebugFullscreen(true);

        assertFalse(defaults.screenshotRequested());
        assertEquals(NrdDiagnostics.Mode.OFF, defaults.nrdDebugView());
        assertEquals(FsrDebugView.OFF, defaults.fsrDebugView());
        assertEquals(DlssRrDebugView.OFF, defaults.rrDebugView());
        assertFalse(defaults.rrDebugFullscreen());
        assertTrue(changed.screenshotRequested());
        assertEquals(NrdDiagnostics.Mode.OPAQUE, changed.nrdDebugView());
        assertEquals(
                FsrDebugView.OVERVIEW,
                changed.fsrDebugView());
        assertEquals(DlssRrDebugView.MOTION, changed.rrDebugView());
        assertTrue(changed.rrDebugFullscreen());
        assertNotSame(defaults, changed);
        assertSame(changed, changed.withRrDebugFullscreen(true));
    }
}
