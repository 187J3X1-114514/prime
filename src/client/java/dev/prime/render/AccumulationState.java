package dev.prime.render;

/** Tracks exactly which rendered sample history is still valid. */
final class AccumulationState {

    private static final int MAXIMUM_EXACT_FLOAT_SAMPLE_INDEX = 16_777_215;
    private static final int DYNAMIC_LIGHTING_HISTORY_SAMPLES = 8;
    private static final int LIGHTING_QUIESCENCE_FRAMES = 8;
    private static final float SUN_HISTORY_DISCONTINUITY_COSINE =
            (float) Math.cos(Math.toRadians(1.0));

    private FrameCamera camera;
    private long resetRevision = Long.MIN_VALUE;
    private long atlasView;
    private long atlasSampler;
    private SunDirection sunDirection;
    private int sampleIndex;
    private int epoch;
    private int lightingStableFrames;
    private boolean dynamicLightingHistory;

    boolean prepare(
        FrameCamera nextCamera,
        long nextResetRevision,
        long nextAtlasView,
        long nextAtlasSampler,
        SunDirection nextSunDirection,
        boolean forceReset
    ) {
        boolean immediateReset =
            forceReset ||
            !sameCamera(nextCamera, this.camera) ||
            nextResetRevision != this.resetRevision ||
            nextAtlasView != this.atlasView ||
            nextAtlasSampler != this.atlasSampler ||
            sunDirectionDiscontinuous(nextSunDirection, this.sunDirection) ||
            this.sampleIndex == MAXIMUM_EXACT_FLOAT_SAMPLE_INDEX;
        if (immediateReset) {
            this.invalidate();
            this.resetRevision = nextResetRevision;
            return true;
        }

        if (!nextSunDirection.equals(this.sunDirection)) {
            this.lightingStableFrames = 0;
            this.dynamicLightingHistory = true;
            this.sampleIndex = Math.min(
                this.sampleIndex,
                DYNAMIC_LIGHTING_HISTORY_SAMPLES - 1
            );
            // The bounded history index would otherwise repeat the same Sobol point every tick.
            // Advancing the scramble epoch keeps samples independent while the sun moves.
            this.epoch++;
            return false;
        }
        if (
            this.dynamicLightingHistory &&
            ++this.lightingStableFrames >= LIGHTING_QUIESCENCE_FRAMES
        ) {
            this.dynamicLightingHistory = false;
            this.lightingStableFrames = 0;
        }
        return false;
    }

    void submitted(
        FrameCamera submittedCamera,
        long submittedAtlasView,
        long submittedAtlasSampler,
        SunDirection submittedSunDirection
    ) {
        this.sampleIndex = this.dynamicLightingHistory
            ? Math.min(
                  this.sampleIndex + 1,
                  DYNAMIC_LIGHTING_HISTORY_SAMPLES - 1
              )
            : this.sampleIndex + 1;
        this.camera = submittedCamera;
        this.atlasView = submittedAtlasView;
        this.atlasSampler = submittedAtlasSampler;
        this.sunDirection = submittedSunDirection;
    }

    void invalidate() {
        this.sampleIndex = 0;
        this.epoch++;
        this.lightingStableFrames = 0;
        this.dynamicLightingHistory = false;
    }

    int sampleIndex() {
        return this.sampleIndex;
    }

    int epoch() {
        return this.epoch;
    }

    private static boolean sunDirectionDiscontinuous(
        SunDirection current,
        SunDirection previous
    ) {
        if (previous == null) {
            return true;
        }
        float cosine = current.x() * previous.x()
            + current.y() * previous.y()
            + current.z() * previous.z();
        return cosine < SUN_HISTORY_DISCONTINUITY_COSINE;
    }

    private static boolean sameCamera(FrameCamera first, FrameCamera second) {
        return (
            first != null &&
            second != null &&
            first
                .inverseViewProjection()
                .equals(second.inverseViewProjection()) &&
            Double.doubleToLongBits(first.x()) ==
                Double.doubleToLongBits(second.x()) &&
            Double.doubleToLongBits(first.y()) ==
                Double.doubleToLongBits(second.y()) &&
            Double.doubleToLongBits(first.z()) ==
                Double.doubleToLongBits(second.z())
        );
    }
}
