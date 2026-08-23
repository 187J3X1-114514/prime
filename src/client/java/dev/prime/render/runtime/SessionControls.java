package dev.prime.render.runtime;

import dev.prime.render.diagnostic.ImageDiagnosticSelection;
import dev.prime.render.diagnostic.NrdInputView;
import dev.prime.render.diagnostic.RendererImageView;
import dev.prime.render.diagnostic.RrInputView;

/** Immutable, non-persistent controls owned by the client runtime. */
public record SessionControls(
        boolean screenshotRequested,
        boolean rendererDiagnostics,
        ImageDiagnosticSelection imageDiagnostics) {
    public SessionControls {
        imageDiagnostics = java.util.Objects.requireNonNull(imageDiagnostics, "imageDiagnostics");
    }

    public static SessionControls defaults() {
        return new SessionControls(
                false,
                false,
                ImageDiagnosticSelection.off());
    }

    public SessionControls withScreenshotRequested(boolean value) {
        return value == this.screenshotRequested
                ? this
                : new SessionControls(
                        value,
                        this.rendererDiagnostics,
                        this.imageDiagnostics);
    }

    public SessionControls withRendererDiagnostics(boolean value) {
        return value == this.rendererDiagnostics
                ? this
                : new SessionControls(
                        this.screenshotRequested,
                        value,
                        this.imageDiagnostics);
    }

    public SessionControls withRendererImageView(RendererImageView value) {
        return withImageDiagnostics(this.imageDiagnostics.withRenderer(value));
    }

    public SessionControls withRrInputView(RrInputView value) {
        return withImageDiagnostics(this.imageDiagnostics.withRr(value));
    }

    public SessionControls withNrdInputView(NrdInputView value) {
        return withImageDiagnostics(this.imageDiagnostics.withNrd(value));
    }

    private SessionControls withImageDiagnostics(ImageDiagnosticSelection value) {
        return value.equals(this.imageDiagnostics)
                ? this
                : new SessionControls(
                        this.screenshotRequested,
                        this.rendererDiagnostics,
                        value);
    }
}
