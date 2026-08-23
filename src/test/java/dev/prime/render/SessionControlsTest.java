package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.diagnostic.NrdInputView;
import dev.prime.render.diagnostic.RendererImageView;
import dev.prime.render.diagnostic.RrInputView;
import org.junit.jupiter.api.Test;

final class SessionControlsTest {
    @Test
    void transitionsPreserveAnImmutableCoherentSnapshot() {
        SessionControls defaults = SessionControls.defaults();
        SessionControls changed = defaults
                .withScreenshotRequested(true)
                .withRendererDiagnostics(true)
                .withRawOutput(true)
                .withRendererImageView(RendererImageView.NORMAL);

        assertFalse(defaults.screenshotRequested());
        assertFalse(defaults.rendererDiagnostics());
        assertFalse(defaults.rawOutput());
        assertEquals(RendererImageView.OFF, defaults.imageDiagnostics().renderer());
        assertEquals(RrInputView.OFF, defaults.imageDiagnostics().rr());
        assertEquals(NrdInputView.OFF, defaults.imageDiagnostics().nrd());
        assertTrue(changed.screenshotRequested());
        assertTrue(changed.rendererDiagnostics());
        assertTrue(changed.rawOutput());
        assertEquals(RendererImageView.NORMAL, changed.imageDiagnostics().renderer());
        assertNotSame(defaults, changed);
        assertSame(changed, changed.withRendererDiagnostics(true));
        assertSame(changed, changed.withRawOutput(true));
    }

    @Test
    void selectingOneImageDomainDisablesTheOtherTwo() {
        SessionControls controls = SessionControls.defaults()
                .withRendererImageView(RendererImageView.GRID)
                .withRrInputView(RrInputView.MOTION);

        assertEquals(RendererImageView.OFF, controls.imageDiagnostics().renderer());
        assertEquals(RrInputView.MOTION, controls.imageDiagnostics().rr());
        assertEquals(NrdInputView.OFF, controls.imageDiagnostics().nrd());

        controls = controls.withNrdInputView(NrdInputView.PRIMARY_NORMAL_ROUGHNESS);
        assertEquals(RendererImageView.OFF, controls.imageDiagnostics().renderer());
        assertEquals(RrInputView.OFF, controls.imageDiagnostics().rr());
        assertEquals(
                NrdInputView.PRIMARY_NORMAL_ROUGHNESS,
                controls.imageDiagnostics().nrd());
    }
}
