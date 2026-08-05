package dev.prime.render;

import java.util.Objects;

/**
 * Pure sample-sequence transition for the interactive render path.
 *
 * <p>A plan exposes the exact Sobol index and epoch used by one frame. The returned state becomes
 * current only after that frame is submitted, so failed recording cannot consume a sample.
 * Only whole-scene continuity lives here. Material, lighting and local scene changes retain the
 * sequence so temporal reconstruction can reject changed pixels without flashing the whole frame.
 */
final class RealtimeSampleState {
    private static final int SOBOL_SEQUENCE_LENGTH = 1 << 16;
    private final FrameCamera camera;
    private final long resetRevision;
    private final int sampleIndex;
    private final int epoch;
    private final boolean resetRequested;

    private RealtimeSampleState(
            FrameCamera camera,
            long resetRevision,
            int sampleIndex,
            int epoch,
            boolean resetRequested) {
        this.camera = camera;
        this.resetRevision = resetRevision;
        this.sampleIndex = sampleIndex;
        this.epoch = epoch;
        this.resetRequested = resetRequested;
    }

    static RealtimeSampleState initial() {
        return new RealtimeSampleState(
                null,
                Long.MIN_VALUE,
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
                || input.resetRevision() != this.resetRevision;
        int plannedSample = reset ? 0 : this.sampleIndex;
        int plannedEpoch = reset ? this.epoch + 1 : this.epoch;
        if (!reset && plannedSample >= SOBOL_SEQUENCE_LENGTH) {
            plannedSample = 0;
            plannedEpoch++;
        }
        RealtimeSampleState committed = new RealtimeSampleState(
                input.camera(),
                input.resetRevision(),
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

    record Input(
            FrameCamera camera,
            long resetRevision,
            boolean forceReset) {
        Input {
            Objects.requireNonNull(camera, "camera");
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
