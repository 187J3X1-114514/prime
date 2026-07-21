package dev.prime.render.fsr;

import dev.prime.render.shader.ShaderAbi;

/** Product-level FidelityFX constants and value types. */
public final class FsrSettings {
    public static final String SDK_VERSION = "1.1.4";
    public static final String UPSCALER_VERSION = "3.1.4";
    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean FRAME_GENERATION_ENABLED = false;
    public static final FsrQualityMode DEFAULT_QUALITY_MODE = FsrQualityMode.PERFORMANCE;
    public static final float RCAS_SHARPNESS = 0.2F;
    /** Prime's display transform currently uses a fixed scene-linear exposure multiplier. */
    public static final float EXPOSURE = ShaderAbi.DISPLAY_EXPOSURE;
    private FsrSettings() {
    }

    public record Extent(int width, int height) {
    }

    /**
     * Centered sub-pixel displacement used by Prime ray generation: sample = pixel center + jitter.
     */
    public record Jitter(float x, float y) {
        /**
         * FSR's public jitterOffset describes the projection displacement. Its shaders recover
         * the unjittered source position as pixel center - jitterOffset, so a ray displaced by
         * +jitter must cross that API boundary as -jitter. NRD instead consumes this record
         * directly because its documented convention is sampleUv = pixelUv + cameraJitter.
         */
        public Jitter forFsrDispatch() {
            return new Jitter(-this.x, -this.y);
        }
    }
}
