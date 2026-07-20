package dev.prime.render.post;

import java.util.Objects;

/** Live product settings shared by configuration, UI, and the renderer. */
public final class PostProcessingSettings {
    private static volatile PostProcessingMode mode = PostProcessingMode.DEFAULT;
    private static volatile ReconstructionQualityMode quality = ReconstructionQualityMode.DEFAULT;
    private static volatile DlssRrDebugView rrDebugView = DlssRrDebugView.OFF;
    private static volatile boolean rrDebugFullscreen;

    private PostProcessingSettings() {
    }

    public static PostProcessingMode mode() {
        return mode;
    }

    public static void setMode(PostProcessingMode value) {
        mode = Objects.requireNonNull(value, "value");
    }

    public static ReconstructionQualityMode quality() {
        return quality;
    }

    public static void setQuality(ReconstructionQualityMode value) {
        quality = Objects.requireNonNull(value, "value");
    }

    public static DlssRrDebugView rrDebugView() {
        return rrDebugView;
    }

    public static void setRrDebugView(DlssRrDebugView value) {
        rrDebugView = Objects.requireNonNull(value, "value");
    }

    public static boolean rrDebugFullscreen() {
        return rrDebugFullscreen;
    }

    public static void setRrDebugFullscreen(boolean value) {
        rrDebugFullscreen = value;
    }
}
