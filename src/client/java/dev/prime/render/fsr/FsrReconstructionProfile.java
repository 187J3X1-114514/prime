package dev.prime.render.fsr;

import dev.prime.render.post.ReconstructionExtent;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import java.util.Objects;

/** FSR-specific size, sampling, mip and ray-cone policy for one product quality. */
public record FsrReconstructionProfile(FsrQualityMode mode) {
    public FsrReconstructionProfile {
        mode = Objects.requireNonNull(mode, "mode");
    }

    public static FsrReconstructionProfile forQuality(ReconstructionQualityMode quality) {
        Objects.requireNonNull(quality, "quality");
        return new FsrReconstructionProfile(switch (quality) {
            case NATIVE_AA -> FsrQualityMode.NATIVE_AA;
            case QUALITY -> FsrQualityMode.QUALITY;
            case BALANCED -> FsrQualityMode.BALANCED;
            case PERFORMANCE -> FsrQualityMode.PERFORMANCE;
            case ULTRA_PERFORMANCE -> FsrQualityMode.ULTRA_PERFORMANCE;
        });
    }

    public ReconstructionExtent renderExtent(int displayWidth, int displayHeight) {
        return new ReconstructionExtent(
                this.mode.renderWidth(displayWidth),
                this.mode.renderHeight(displayHeight));
    }

    public float upscaleRatio() {
        return this.mode.upscaleRatio();
    }

    public float mipBias() {
        return this.mode.mipBias();
    }

    public int packedRayCone(
            float projectionM00, float projectionM11, int width, int height) {
        return this.mode.packedRayCone(projectionM00, projectionM11, width, height);
    }

    public SubpixelJitter jitter(int frameIndex) {
        return this.mode.jitter(frameIndex);
    }

    public int jitterPhase(int frameIndex) {
        return this.mode.jitterPhase(frameIndex);
    }
}
