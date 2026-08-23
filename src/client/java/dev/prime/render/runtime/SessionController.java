package dev.prime.render.runtime;

import dev.prime.render.diagnostic.NrdInputView;
import dev.prime.render.diagnostic.RendererImageView;
import dev.prime.render.diagnostic.RrInputView;
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
        this.previous = current;
    }

    public void requestScreenshot(boolean value) {
        this.controls = this.controls.withScreenshotRequested(value);
    }

    public void setRendererDiagnostics(boolean value) {
        this.controls = this.controls.withRendererDiagnostics(value);
    }

    public void setRendererImageView(RendererImageView value) {
        this.controls = this.controls.withRendererImageView(value);
    }

    public void setRrInputView(RrInputView value) {
        this.controls = this.controls.withRrInputView(value);
    }

    public void setNrdInputView(NrdInputView value) {
        this.controls = this.controls.withNrdInputView(value);
    }

    public void restoreDefaults() {
        this.controls = SessionControls.defaults();
    }

    public record KeyState(boolean escape) {
        public static final KeyState NONE = new KeyState(false);
    }
}
