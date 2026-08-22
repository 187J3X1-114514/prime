package dev.prime.render.post;

import java.util.ArrayList;
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
        ArrayList<String> result = new ArrayList<>(List.of(
                "Prime  DLSS RR  " + quality.id(),
                "Input " + renderWidth + "x" + renderHeight
                        + "  Output " + outputWidth + "x" + outputHeight,
                "NGX " + (available ? "available" : "unavailable")
                        + "  Preset Default  Reset " + (reset ? "yes" : "no"),
                "View " + view.id()
                        + "  " + (fullscreen ? "fullscreen" : "top-right panel"),
                "Ctrl+Alt+F12 view  Ctrl+Alt+F11 layout"));
        if (view == DlssRrDebugView.WAVEFRONT_OVERVIEW) {
            result.add("Top specular-albedo diffuse-hit specular-hit metadata reflection-pos");
            result.add("Bottom diffuse specular normal roughness diffuse-albedo");
        } else if (view == DlssRrDebugView.HANDOFF_OVERVIEW) {
            result.add("Columns normal roughness diffuse-albedo specular-albedo specular-hit");
            result.add("Top RR-submitted / Bottom wavefront-scratch");
        } else if (view == DlssRrDebugView.GUIDE_RESOLVE_OVERVIEW) {
            result.add("Columns visible final-trans first-owned-trans selected flags");
            result.add("Rows normal roughness diffuse-albedo specular-albedo");
            result.add("Flags bottom: final reached / primary-bounce-zero / fallback (RGB)");
        }
        return List.copyOf(result);
    }
}
