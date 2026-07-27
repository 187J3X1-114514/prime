package dev.prime.render.fsr;

import dev.prime.render.shader.ShaderAbi;

/** Product-level FidelityFX constants and value types. */
public final class FsrSettings {
    public static final String SDK_VERSION = "1.1.4";
    public static final String UPSCALER_VERSION = ShaderAbi.FSR_VERSION;
    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean FRAME_GENERATION_ENABLED = false;
    public static final FsrQualityMode DEFAULT_QUALITY_MODE = FsrQualityMode.PERFORMANCE;
    public static final float RCAS_SHARPNESS = 0.2F;
    /** Prime's display transform currently uses a fixed scene-linear exposure multiplier. */
    public static final float EXPOSURE = ShaderAbi.DISPLAY_EXPOSURE;
    private FsrSettings() {
    }

    public record Extent(int width, int height) {
        public Extent {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException(
                        "FSR extent must be positive");
            }
        }
    }

    /**
     * Centered sub-pixel displacement used by Prime ray generation: sample = pixel center + jitter.
     */
    public record Jitter(float x, float y) {
        public Jitter {
            if (!Float.isFinite(x)
                    || !Float.isFinite(y)
                    || Math.abs(x) > 0.5F
                    || Math.abs(y) > 0.5F) {
                throw new IllegalArgumentException(
                        "FSR jitter must be finite and inside one source pixel");
            }
        }

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
