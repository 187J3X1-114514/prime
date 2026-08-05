package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.post.nrd.NrdDiagnostics;
import org.junit.jupiter.api.Test;

final class SessionControlsTest {
    @Test
    void transitionsPreserveAnImmutableCoherentSnapshot() {
        SessionControls defaults = SessionControls.defaults();
        SessionControls changed = defaults
                .withScreenshotRequested(true)
                .withTriangleDebug(true)
                .withRendererDiagnostics(true)
                .withNrdDebugView(NrdDiagnostics.Mode.NATIVE_VALIDATION)
                .withFsrDebugView(FsrDebugView.OVERVIEW)
                .withRrDebugView(DlssRrDebugView.MOTION)
                .withRrDebugFullscreen(true);

        assertFalse(defaults.screenshotRequested());
        assertFalse(defaults.triangleDebug());
        assertFalse(defaults.rendererDiagnostics());
        assertEquals(NrdDiagnostics.Mode.OFF, defaults.nrdDebugView());
        assertEquals(FsrDebugView.OFF, defaults.fsrDebugView());
        assertEquals(DlssRrDebugView.OFF, defaults.rrDebugView());
        assertFalse(defaults.rrDebugFullscreen());
        assertTrue(changed.screenshotRequested());
        assertTrue(changed.triangleDebug());
        assertTrue(changed.rendererDiagnostics());
        assertEquals(NrdDiagnostics.Mode.NATIVE_VALIDATION, changed.nrdDebugView());
        assertEquals(
                FsrDebugView.OVERVIEW,
                changed.fsrDebugView());
        assertEquals(DlssRrDebugView.MOTION, changed.rrDebugView());
        assertTrue(changed.rrDebugFullscreen());
        assertNotSame(defaults, changed);
        assertSame(changed, changed.withRrDebugFullscreen(true));
        assertSame(changed, changed.withRendererDiagnostics(true));
    }
}
