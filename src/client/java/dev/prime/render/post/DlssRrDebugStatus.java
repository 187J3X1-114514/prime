package dev.prime.render.post;

import java.util.List;

/** Immutable render-thread snapshot consumed by Minecraft's HUD extraction. */
public final class DlssRrDebugStatus {
    private static volatile Snapshot snapshot;

    private DlssRrDebugStatus() {}

    public static void update(
            ReconstructionQualityMode quality,
            int renderWidth,
            int renderHeight,
            int outputWidth,
            int outputHeight,
            boolean available,
            boolean reset) {
        DlssRrDebugView view = PostProcessingSettings.rrDebugView();
        if (view == DlssRrDebugView.OFF) {
            snapshot = null;
            return;
        }
        snapshot = new Snapshot(
                quality,
                renderWidth,
                renderHeight,
                outputWidth,
                outputHeight,
                available,
                reset,
                view,
                PostProcessingSettings.rrDebugFullscreen());
    }

    public static void clear() {
        snapshot = null;
    }

    public static List<String> lines() {
        Snapshot value = snapshot;
        if (value == null) return List.of();
        return List.of(
                "Prime  DLSS RR  " + value.quality().id(),
                "Input " + value.renderWidth() + "x" + value.renderHeight()
                        + "  Output " + value.outputWidth() + "x" + value.outputHeight(),
                "NGX " + (value.available() ? "available" : "unavailable")
                        + "  Preset Default  Reset " + (value.reset() ? "yes" : "no"),
                "View " + value.view().id()
                        + "  " + (value.fullscreen() ? "fullscreen" : "top-right panel"),
                "Ctrl+Alt+F12 view  Ctrl+Alt+F11 layout");
    }

    private record Snapshot(
            ReconstructionQualityMode quality,
            int renderWidth,
            int renderHeight,
            int outputWidth,
            int outputHeight,
            boolean available,
            boolean reset,
            DlssRrDebugView view,
            boolean fullscreen) {}
}
