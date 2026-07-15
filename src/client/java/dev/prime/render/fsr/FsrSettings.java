package dev.prime.render.fsr;

import dev.prime.render.shader.ShaderAbi;
import java.util.Objects;

/** Product-level FidelityFX Super Resolution settings shared by configuration and rendering. */
public final class FsrSettings {
    public static final String SDK_VERSION = "1.1.4";
    public static final String UPSCALER_VERSION = "3.1.5";
    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean FRAME_GENERATION_ENABLED = false;
    public static final FsrQualityMode DEFAULT_QUALITY_MODE = FsrQualityMode.QUALITY;
    public static final float RCAS_SHARPNESS = 0.2F;
    /** Prime's display transform currently uses a fixed scene-linear exposure multiplier. */
    public static final float EXPOSURE = ShaderAbi.DISPLAY_EXPOSURE;
    private static volatile FsrQualityMode qualityMode = DEFAULT_QUALITY_MODE;
    private static volatile FsrDebugView debugView = FsrDebugView.OFF;

    private FsrSettings() {
    }

    public static FsrQualityMode qualityMode() {
        return qualityMode;
    }

    public static void setQualityMode(FsrQualityMode mode) {
        qualityMode = Objects.requireNonNull(mode, "mode");
    }

    public static FsrDebugView debugView() {
        return debugView;
    }

    public static void setDebugView(FsrDebugView mode) {
        debugView = Objects.requireNonNull(mode, "mode");
    }

    /** Official RCAS configuration value after FSR's public 0..1 sharpness remapping. */
    public static float rcasLinearSharpness() {
        float stops = -2.0F * RCAS_SHARPNESS + 2.0F;
        return (float) Math.pow(2.0, -stops);
    }

    public record Extent(int width, int height) {
    }

    public record Jitter(float x, float y) {
    }
}
