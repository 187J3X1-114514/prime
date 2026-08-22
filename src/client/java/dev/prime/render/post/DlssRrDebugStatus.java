package dev.prime.render.post;

import java.util.List;

/** Formats the RR frame state published by the renderer for Minecraft's HUD. */
public final class DlssRrDebugStatus {
    private DlssRrDebugStatus() {}

    public static List<String> lines(
            ReconstructionQualityMode quality,
            int renderWidth,
            int renderHeight,
            int outputWidth,
            int outputHeight,
            boolean available,
            boolean reset,
            DlssRrDebugView view,
            boolean fullscreen) {
        if (view == DlssRrDebugView.OFF) {
            return List.of();
        }
        return List.of(
                "Prime  DLSS RR  " + quality.id(),
                "Input " + renderWidth + "x" + renderHeight
                        + "  Output " + outputWidth + "x" + outputHeight,
                "NGX " + (available ? "available" : "unavailable")
                        + "  Preset F  Reset " + (reset ? "yes" : "no"),
                "View " + view.id()
                        + "  " + (fullscreen ? "fullscreen" : "top-right panel"),
                "Ctrl+Alt+F12 view  Ctrl+Alt+F11 layout");
    }
}
