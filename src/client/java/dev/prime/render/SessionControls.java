package dev.prime.render;

import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import java.util.Objects;

/** Immutable, non-persistent controls owned by the client runtime. */
public record SessionControls(
        boolean screenshotRequested,
        boolean triangleDebug,
        NrdDiagnostics.Mode nrdDebugView,
        FsrDebugView fsrDebugView,
        DlssRrDebugView rrDebugView,
        boolean rrDebugFullscreen) {
    public SessionControls {
        nrdDebugView = Objects.requireNonNull(nrdDebugView, "nrdDebugView");
        fsrDebugView = Objects.requireNonNull(fsrDebugView, "fsrDebugView");
        rrDebugView = Objects.requireNonNull(rrDebugView, "rrDebugView");
    }

    public static SessionControls defaults() {
        return new SessionControls(
                false,
                false,
                NrdDiagnostics.Mode.OFF,
                FsrDebugView.OFF,
                DlssRrDebugView.OFF,
                false);
    }

    public SessionControls withScreenshotRequested(boolean value) {
        return value == this.screenshotRequested
                ? this
                : new SessionControls(
                        value,
                        this.triangleDebug,
                        this.nrdDebugView,
                        this.fsrDebugView,
                        this.rrDebugView,
                        this.rrDebugFullscreen);
    }

    public SessionControls withTriangleDebug(boolean value) {
        return value == this.triangleDebug
                ? this
                : new SessionControls(
                        this.screenshotRequested,
                        value,
                        this.nrdDebugView,
                        this.fsrDebugView,
                        this.rrDebugView,
                        this.rrDebugFullscreen);
    }

    public SessionControls withNrdDebugView(NrdDiagnostics.Mode value) {
        Objects.requireNonNull(value, "value");
        return value == this.nrdDebugView
                ? this
                : new SessionControls(
                        this.screenshotRequested,
                        this.triangleDebug,
                        value,
                        this.fsrDebugView,
                        this.rrDebugView,
                        this.rrDebugFullscreen);
    }

    public SessionControls withFsrDebugView(FsrDebugView value) {
        Objects.requireNonNull(value, "value");
        return value == this.fsrDebugView
                ? this
                : new SessionControls(
                        this.screenshotRequested,
                        this.triangleDebug,
                        this.nrdDebugView,
                        value,
                        this.rrDebugView,
                        this.rrDebugFullscreen);
    }

    public SessionControls withRrDebugView(DlssRrDebugView value) {
        Objects.requireNonNull(value, "value");
        return value == this.rrDebugView
                ? this
                : new SessionControls(
                        this.screenshotRequested,
                        this.triangleDebug,
                        this.nrdDebugView,
                        this.fsrDebugView,
                        value,
                        this.rrDebugFullscreen);
    }

    public SessionControls withRrDebugFullscreen(boolean value) {
        return value == this.rrDebugFullscreen
                ? this
                : new SessionControls(
                        this.screenshotRequested,
                        this.triangleDebug,
                        this.nrdDebugView,
                        this.fsrDebugView,
                        this.rrDebugView,
                        value);
    }
}
