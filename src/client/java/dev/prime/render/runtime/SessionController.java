package dev.prime.render.runtime;

import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.LightSamplingDiagnostic;
import dev.prime.render.PrimaryLightDiagnosticView;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.post.nrd.NrdDiagnostics;
import java.util.Objects;

/** Client-thread-owned shortcut edges and non-persistent session controls. */
public final class SessionController {
    private SessionControls controls = SessionControls.defaults();
    private KeyState previous = KeyState.NONE;

    public SessionControls controls() {
        return this.controls;
    }

    public void update(KeyState current, boolean screenshotActive) {
        Objects.requireNonNull(current, "current");
        if (current.escape()
                && !this.previous.escape()
                && (this.controls.screenshotRequested() || screenshotActive)) {
            requestScreenshot(false);
        }
        if (current.rrCycle() && !this.previous.rrCycle()) {
            setRrDebugView(this.controls.rrDebugView().next());
        }
        if (current.rrLayout() && !this.previous.rrLayout()) {
            setRrDebugFullscreen(!this.controls.rrDebugFullscreen());
        }
        this.previous = current;
    }

    public void requestScreenshot(boolean value) {
        this.controls = this.controls.withScreenshotRequested(value);
    }

    public void setTriangleDebug(boolean value) {
        this.controls = this.controls.withTriangleDebug(value);
    }

    public void setRendererDiagnostics(boolean value) {
        this.controls = this.controls.withRendererDiagnostics(value);
    }

    public void setLightSamplingDiagnostic(LightSamplingDiagnostic value) {
        this.controls = this.controls.withLightSamplingDiagnostic(value);
    }

    public void setPrimaryLightDiagnosticView(PrimaryLightDiagnosticView value) {
        this.controls = this.controls.withPrimaryLightDiagnosticView(value);
    }

    public void setNrdDebugView(NrdDiagnostics.Mode value) {
        this.controls = this.controls.withNrdDebugView(value);
    }

    public void setFsrDebugView(FsrDebugView value) {
        this.controls = this.controls.withFsrDebugView(value);
    }

    public void setRrDebugView(DlssRrDebugView value) {
        this.controls = this.controls.withRrDebugView(value);
    }

    public void setRrDebugFullscreen(boolean value) {
        this.controls = this.controls.withRrDebugFullscreen(value);
    }

    public void restoreDefaults() {
        this.controls = SessionControls.defaults();
    }

    public record KeyState(
            boolean escape,
            boolean rrCycle,
            boolean rrLayout) {
        public static final KeyState NONE = new KeyState(false, false, false);
    }
}
