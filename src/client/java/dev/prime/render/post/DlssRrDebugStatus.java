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
                        + "  Preset F  Reset " + (reset ? "yes" : "no"),
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
            result.add("Trace miss: blue no-candidate / cyan exact-source / magenta paired-source");
            result.add("Trace miss: orange cutout / yellow mixed / orange SER-only");
            result.add("Unresolved col 3 rows: source / trace / discrete chain / terminal direction");
            result.add("Source: cyan solid-enter / blue bad-direction / orange unexpected-thin");
            result.add("Discrete chain: black 0 / red 1 / green 2 / yellow 3 / blue 4+ / magenta overflow");
            result.add("Terminal direction: green aligned / cyan forward / yellow backward / red reverse / magenta invalid");
            result.add("Flags bottom unresolved: pink reflection-only / gray inactive");
        }
        return List.copyOf(result);
    }
}
