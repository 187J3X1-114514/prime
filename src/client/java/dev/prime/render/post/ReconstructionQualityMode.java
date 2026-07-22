package dev.prime.render.post;

import dev.prime.render.fsr.FsrQualityMode;
import dev.prime.render.fsr.FsrSettings;
import java.util.Arrays;
import java.util.Optional;

/** Shared product quality setting with backend-specific resolution selection. */
public enum ReconstructionQualityMode {
    NATIVE_AA("native_aa", FsrQualityMode.NATIVE_AA, 5),
    QUALITY("quality", FsrQualityMode.QUALITY, 2),
    BALANCED("balanced", FsrQualityMode.BALANCED, 1),
    PERFORMANCE("performance", FsrQualityMode.PERFORMANCE, 0),
    ULTRA_PERFORMANCE("ultra_performance", FsrQualityMode.ULTRA_PERFORMANCE, 3);

    public static final ReconstructionQualityMode DEFAULT = PERFORMANCE;

    private final String id;
    private final FsrQualityMode fsrMode;
    private final int ngxPerfQualityValue;

    ReconstructionQualityMode(String id, FsrQualityMode fsrMode, int ngxPerfQualityValue) {
        this.id = id;
        this.fsrMode = fsrMode;
        this.ngxPerfQualityValue = ngxPerfQualityValue;
    }

    public String id() {
        return this.id;
    }

    public FsrQualityMode fsrMode() {
        return this.fsrMode;
    }

    /** Native NVSDK_NGX_PerfQuality_Value numeric value. */
    public int ngxPerfQualityValue() {
        return this.ngxPerfQualityValue;
    }

    public int renderWidth(int displayWidth) {
        return this.fsrMode.renderWidth(displayWidth);
    }

    public int renderHeight(int displayHeight) {
        return this.fsrMode.renderHeight(displayHeight);
    }

    public float upscaleRatio() {
        return this.fsrMode.upscaleRatio();
    }

    public float mipBias() {
        return this.fsrMode.mipBias();
    }

    public int packedRayCone(float projectionM00, float projectionM11, int width, int height) {
        return this.fsrMode.packedRayCone(projectionM00, projectionM11, width, height);
    }

    public FsrSettings.Jitter fsrJitter(int frameIndex) {
        return this.fsrMode.jitter(frameIndex);
    }

    public int fsrJitterPhase(int frameIndex) {
        return this.fsrMode.jitterPhase(frameIndex);
    }

    /** RR uses a long Halton cycle to avoid a visible short-period sub-pixel pattern. */
    public int rrJitterPhaseCount() {
        return Math.max(64, this.fsrMode.jitterPhaseCount());
    }

    public int rrJitterPhase(int frameIndex) {
        return Math.floorMod(frameIndex, this.rrJitterPhaseCount()) + 1;
    }

    public FsrSettings.Jitter rrJitter(int frameIndex) {
        int phase = this.rrJitterPhase(frameIndex);
        return new FsrSettings.Jitter(
                halton(phase, 2) - 0.5F,
                halton(phase, 3) - 0.5F);
    }

    public static Optional<ReconstructionQualityMode> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst();
    }

    public static ReconstructionQualityMode fromId(String id) {
        return findById(id).orElse(DEFAULT);
    }

    private static float halton(int index, int base) {
        float result = 0.0F;
        float fraction = 1.0F;
        int value = index;
        while (value > 0) {
            fraction /= base;
            result += fraction * (value % base);
            value /= base;
        }
        return result;
    }
}
