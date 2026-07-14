package dev.prime.render;

/** Tracks exactly which rendered sample history is still valid. */
final class AccumulationState {

    private static final int MAXIMUM_EXACT_FLOAT_SAMPLE_INDEX = 16_777_215;
    private static final int TRANSIENT_SCENE_HISTORY_SAMPLES = 8;
    private static final int SCENE_QUIESCENCE_FRAMES = 8;

    private FrameCamera camera;
    private long observedSceneRevision = Long.MIN_VALUE;
    private long resetRevision = Long.MIN_VALUE;
    private long atlasView;
    private long atlasSampler;
    private SunDirection sunDirection;
    private int sampleIndex;
    private int epoch;
    private int sceneStableFrames;
    private boolean sceneHistoryMixed;

    boolean prepare(
        FrameCamera nextCamera,
        long nextSceneRevision,
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
            !nextSunDirection.equals(this.sunDirection) ||
            this.sampleIndex == MAXIMUM_EXACT_FLOAT_SAMPLE_INDEX;
        if (immediateReset) {
            this.invalidate();
            this.observedSceneRevision = nextSceneRevision;
            this.resetRevision = nextResetRevision;
            return true;
        }

        if (nextSceneRevision != this.observedSceneRevision) {
            this.observedSceneRevision = nextSceneRevision;
            this.sceneStableFrames = 0;
            this.sceneHistoryMixed = true;
            this.sampleIndex = Math.min(
                this.sampleIndex,
                TRANSIENT_SCENE_HISTORY_SAMPLES - 1
            );
            this.epoch++;
            return false;
        }
        if (
            this.sceneHistoryMixed &&
            ++this.sceneStableFrames >= SCENE_QUIESCENCE_FRAMES
        ) {
            this.invalidate();
            return true;
        }
        return false;
    }

    void submitted(
        FrameCamera submittedCamera,
        long submittedAtlasView,
        long submittedAtlasSampler,
        SunDirection submittedSunDirection
    ) {
        this.sampleIndex = this.sceneHistoryMixed
            ? Math.min(
                  this.sampleIndex + 1,
                  TRANSIENT_SCENE_HISTORY_SAMPLES - 1
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
        this.sceneStableFrames = 0;
        this.sceneHistoryMixed = false;
    }

    int sampleIndex() {
        return this.sampleIndex;
    }

    int epoch() {
        return this.epoch;
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
