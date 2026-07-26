package dev.prime.render;

import java.util.Objects;

/**
 * Pure sample-sequence transition for the interactive render path.
 *
 * <p>A plan exposes the exact Sobol index and epoch used by one frame. The returned state becomes
 * current only after that frame is submitted, so failed recording cannot consume a sample.
 */
final class RealtimeSampleState {
    private static final int SOBOL_SEQUENCE_LENGTH = 1 << 16;
    private static final float SUN_DISCONTINUITY_COSINE =
            (float) Math.cos(Math.toRadians(1.0));

    private final FrameCamera camera;
    private final long resetRevision;
    private final long textureRevision;
    private final SunDirection sunDirection;
    private final int sampleIndex;
    private final int epoch;

    private RealtimeSampleState(
            FrameCamera camera,
            long resetRevision,
            long textureRevision,
            SunDirection sunDirection,
            int sampleIndex,
            int epoch) {
        this.camera = camera;
        this.resetRevision = resetRevision;
        this.textureRevision = textureRevision;
        this.sunDirection = sunDirection;
        this.sampleIndex = sampleIndex;
        this.epoch = epoch;
    }

    static RealtimeSampleState initial() {
        return new RealtimeSampleState(
                null, Long.MIN_VALUE, Long.MIN_VALUE, null, 0, 0);
    }

    Plan plan(Input input) {
        Objects.requireNonNull(input, "input");
        // Motion vectors preserve ordinary camera motion. Restarting on every translated or
        // rotated frame destroys temporal Sobol stratification and raises 1 spp noise.
        boolean reset = input.forceReset()
                || CameraDiscontinuity.isCut(this.camera, input.camera())
                || input.resetRevision() != this.resetRevision
                || input.textureRevision() != this.textureRevision
                || sunDirectionDiscontinuous(input.sunDirection(), this.sunDirection);
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
                input.sunDirection(),
                plannedSample + 1,
                plannedEpoch);
        return new Plan(plannedSample, plannedEpoch, reset, committed);
    }

    RealtimeSampleState invalidated() {
        return new RealtimeSampleState(
                this.camera,
                this.resetRevision,
                this.textureRevision,
                this.sunDirection,
                0,
                this.epoch + 1);
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
        return cosine < SUN_DISCONTINUITY_COSINE;
    }

    record Input(
            FrameCamera camera,
            long resetRevision,
            long textureRevision,
            SunDirection sunDirection,
            boolean forceReset) {
        Input {
            Objects.requireNonNull(camera, "camera");
            Objects.requireNonNull(sunDirection, "sunDirection");
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
