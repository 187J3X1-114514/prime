package dev.prime.render;

import java.util.Objects;

/**
 * Pure sample-sequence transition for the interactive render path.
 *
 * <p>A plan exposes the exact Sobol index and epoch used by one frame. The returned state becomes
 * current only after that frame is submitted, so failed recording cannot consume a sample.
 * Scene, texture, lighting, material and camera-medium identities live here rather than in
 * parallel renderer fields so every temporal reset derives from one committed state.
 */
final class RealtimeSampleState {
    private static final int SOBOL_SEQUENCE_LENGTH = 1 << 16;
    private static final float SUN_DISCONTINUITY_COSINE =
            (float) Math.cos(Math.toRadians(1.0));

    private final FrameCamera camera;
    private final long resetRevision;
    private final long textureRevision;
    private final long lightingRevision;
    private final long materialRevision;
    private final AstronomyState astronomy;
    private final boolean cameraInWater;
    private final WavefrontDebugMode wavefrontDebugMode;
    private final int sampleIndex;
    private final int epoch;
    private final boolean resetRequested;

    private RealtimeSampleState(
            FrameCamera camera,
            long resetRevision,
            long textureRevision,
            long lightingRevision,
            long materialRevision,
            AstronomyState astronomy,
            boolean cameraInWater,
            WavefrontDebugMode wavefrontDebugMode,
            int sampleIndex,
            int epoch,
            boolean resetRequested) {
        this.camera = camera;
        this.resetRevision = resetRevision;
        this.textureRevision = textureRevision;
        this.lightingRevision = lightingRevision;
        this.materialRevision = materialRevision;
        this.astronomy = astronomy;
        this.cameraInWater = cameraInWater;
        this.wavefrontDebugMode = wavefrontDebugMode;
        this.sampleIndex = sampleIndex;
        this.epoch = epoch;
        this.resetRequested = resetRequested;
    }

    static RealtimeSampleState initial() {
        return new RealtimeSampleState(
                null,
                Long.MIN_VALUE,
                Long.MIN_VALUE,
                Long.MIN_VALUE,
                Long.MIN_VALUE,
                null,
                false,
                WavefrontDebugMode.BASELINE,
                0,
                0,
                true);
    }

    Plan plan(Input input) {
        Objects.requireNonNull(input, "input");
        // Motion vectors preserve ordinary camera motion. Restarting on every translated or
        // rotated frame destroys temporal Sobol stratification and raises 1 spp noise.
        boolean reset = this.resetRequested
                || input.forceReset()
                || CameraDiscontinuity.isCut(this.camera, input.camera())
                || input.resetRevision() != this.resetRevision
                || input.textureRevision() != this.textureRevision
                || input.lightingRevision() != this.lightingRevision
                || input.materialRevision() != this.materialRevision
                || (this.camera != null
                        && input.cameraInWater() != this.cameraInWater)
                || input.wavefrontDebugMode() != this.wavefrontDebugMode
                || astronomyDiscontinuous(input.astronomy(), this.astronomy);
        int plannedSample = reset ? 0 : this.sampleIndex;
        int plannedEpoch = reset ? this.epoch + 1 : this.epoch;
        if (!reset && plannedSample >= SOBOL_SEQUENCE_LENGTH) {
            plannedSample = 0;
            plannedEpoch++;
        }
        RealtimeSampleState committed = new RealtimeSampleState(
                input.camera(),
                input.resetRevision(),
                input.textureRevision(),
                input.lightingRevision(),
                input.materialRevision(),
                input.astronomy(),
                input.cameraInWater(),
                input.wavefrontDebugMode(),
                plannedSample + 1,
                plannedEpoch,
                false);
        return new Plan(plannedSample, plannedEpoch, reset, committed);
    }

    RealtimeSampleState invalidated() {
        if (this.resetRequested) {
            return this;
        }
        return new RealtimeSampleState(
                this.camera,
                this.resetRevision,
                this.textureRevision,
                this.lightingRevision,
                this.materialRevision,
                this.astronomy,
                this.cameraInWater,
                this.wavefrontDebugMode,
                this.sampleIndex,
                this.epoch,
                true);
    }

    int sampleIndex() {
        return this.sampleIndex;
    }

    int epoch() {
        return this.epoch;
    }

    private static boolean astronomyDiscontinuous(
            AstronomyState current,
            AstronomyState previous) {
        if (previous == null) {
            return true;
        }
        if (!current.settings().equals(previous.settings())) {
            return true;
        }
        SunDirection currentSun = current.sunDirection();
        SunDirection previousSun = previous.sunDirection();
        float cosine = currentSun.x() * previousSun.x()
                + currentSun.y() * previousSun.y()
                + currentSun.z() * previousSun.z();
        return cosine < SUN_DISCONTINUITY_COSINE;
    }

    record Input(
            FrameCamera camera,
            long resetRevision,
            long textureRevision,
            long lightingRevision,
            long materialRevision,
            AstronomyState astronomy,
            boolean cameraInWater,
            WavefrontDebugMode wavefrontDebugMode,
            boolean forceReset) {
        Input {
            Objects.requireNonNull(camera, "camera");
            Objects.requireNonNull(astronomy, "astronomy");
            Objects.requireNonNull(wavefrontDebugMode, "wavefrontDebugMode");
        }
    }

    record Plan(
            int sampleIndex,
            int epoch,
            boolean reset,
            RealtimeSampleState committedState) {
        Plan {
            if (sampleIndex < 0 || sampleIndex >= SOBOL_SEQUENCE_LENGTH) {
                throw new IllegalArgumentException("Sobol sample index is outside its sequence");
            }
            Objects.requireNonNull(committedState, "committedState");
        }
    }
}
