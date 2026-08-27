package dev.prime.render.runtime;

import dev.prime.render.diagnostic.ImageDiagnosticSelection;
import dev.prime.render.diagnostic.NrdInputView;
import dev.prime.render.diagnostic.RendererImageView;
import dev.prime.render.diagnostic.RrInputView;
import dev.prime.render.diagnostic.RrResponsivity;

/** Immutable, non-persistent controls owned by the client runtime. */
public record SessionControls(
        boolean screenshotRequested,
        boolean rendererDiagnostics,
        boolean rawOutput,
        ImageDiagnosticSelection imageDiagnostics,
        float rrResponsivity) {
    public SessionControls {
        imageDiagnostics = java.util.Objects.requireNonNull(imageDiagnostics, "imageDiagnostics");
        rrResponsivity = RrResponsivity.requireValid(rrResponsivity);
    }

    public static SessionControls defaults() {
        return new SessionControls(
                false,
                false,
                false,
                ImageDiagnosticSelection.off(),
                RrResponsivity.DEFAULT);
    }

    public SessionControls withScreenshotRequested(boolean value) {
        return value == this.screenshotRequested
                ? this
                : new SessionControls(
                        value,
                        this.rendererDiagnostics,
                        this.rawOutput,
                        this.imageDiagnostics,
                        this.rrResponsivity);
    }

    public SessionControls withRendererDiagnostics(boolean value) {
        return value == this.rendererDiagnostics
                ? this
                : new SessionControls(
                        this.screenshotRequested,
                        value,
                        this.rawOutput,
                        this.imageDiagnostics,
                        this.rrResponsivity);
    }

    public SessionControls withRawOutput(boolean value) {
        return value == this.rawOutput
                ? this
                : new SessionControls(
                        this.screenshotRequested,
                        this.rendererDiagnostics,
                        value,
                        this.imageDiagnostics,
                        this.rrResponsivity);
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

    public SessionControls withRrResponsivity(float value) {
        value = RrResponsivity.requireValid(value);
        return value == this.rrResponsivity
                ? this
                : new SessionControls(
                        this.screenshotRequested,
                        this.rendererDiagnostics,
                        this.rawOutput,
                        this.imageDiagnostics,
                        value);
    }

    private SessionControls withImageDiagnostics(ImageDiagnosticSelection value) {
        return value.equals(this.imageDiagnostics)
                ? this
                : new SessionControls(
                        this.screenshotRequested,
                        this.rendererDiagnostics,
                        this.rawOutput,
                        value,
                        this.rrResponsivity);
    }
}
