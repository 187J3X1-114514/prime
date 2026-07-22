package dev.prime.render;

/**
 * Tracks sample sequencing and temporal invalidation for the interactive render path.
 *
 * <p>This is not a radiance accumulator. NRD and FSR own the interactive temporal histories;
 * this state only supplies their sampling epoch and monotonic sample index. The future offline
 * path must own a separate, monotonic sample counter and an explicit RGBA32F running mean.
 */
final class RealtimeSampleState {

    private static final int SOBOL_SEQUENCE_LENGTH = 1 << 16;
    private static final float SUN_HISTORY_DISCONTINUITY_COSINE =
            (float) Math.cos(Math.toRadians(1.0));

    private FrameCamera camera;
    private long resetRevision = Long.MIN_VALUE;
    private long atlasView;
    private long atlasSampler;
    private SunDirection sunDirection;
    private int sampleIndex;
    private int epoch;

    boolean prepare(
            FrameCamera nextCamera,
            long nextResetRevision,
            long nextAtlasView,
            long nextAtlasSampler,
            SunDirection nextSunDirection,
            boolean forceReset) {
        // Motion vectors preserve ordinary camera motion. Restarting the Sobol epoch on every
        // translated or rotated frame destroys its temporal stratification and raises 1 spp noise.
        boolean immediateReset = forceReset
                || CameraDiscontinuity.isCut(this.camera, nextCamera)
                || nextResetRevision != this.resetRevision
                || nextAtlasView != this.atlasView
                || nextAtlasSampler != this.atlasSampler
                || sunDirectionDiscontinuous(nextSunDirection, this.sunDirection);
        if (immediateReset) {
            this.invalidate();
            this.resetRevision = nextResetRevision;
            return true;
        }
        if (this.sampleIndex >= SOBOL_SEQUENCE_LENGTH) {
            this.sampleIndex = 0;
            this.epoch++;
        }

        return false;
    }

    void submitted(
            FrameCamera submittedCamera,
            long submittedAtlasView,
            long submittedAtlasSampler,
            SunDirection submittedSunDirection) {
        this.sampleIndex++;
        this.camera = submittedCamera;
        this.atlasView = submittedAtlasView;
        this.atlasSampler = submittedAtlasSampler;
        this.sunDirection = submittedSunDirection;
    }

    void invalidate() {
        this.sampleIndex = 0;
        this.epoch++;
    }

    int sampleIndex() {
        return this.sampleIndex;
    }

    int epoch() {
        return this.epoch;
    }

    private static boolean sunDirectionDiscontinuous(
            SunDirection current,
            SunDirection previous) {
        if (previous == null) {
            return true;
        }
        float cosine = current.x() * previous.x()
                + current.y() * previous.y()
                + current.z() * previous.z();
        return cosine < SUN_HISTORY_DISCONTINUITY_COSINE;
    }

}
