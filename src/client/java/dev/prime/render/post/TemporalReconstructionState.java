package dev.prime.render.post;

import dev.prime.render.CameraDiscontinuity;
import dev.prime.render.FrameCamera;
import dev.prime.render.FrameTime;
import java.util.Objects;

/**
 * Pure temporal identity transition shared by reconstruction backends.
 *
 * <p>{@link Plan#committedState()} becomes current only after the command buffer is submitted.
 * Planning or failed recording therefore cannot consume a history frame.
 */
public final class TemporalReconstructionState {
    private final FrameCamera camera;
    private final long sceneRevision;
    private final int nextFrameIndex;
    private final long frameTimeNanos;
    private final boolean restartRequested;

    private TemporalReconstructionState(
            FrameCamera camera,
            long sceneRevision,
            int nextFrameIndex,
            long frameTimeNanos,
            boolean restartRequested) {
        this.camera = camera;
        this.sceneRevision = sceneRevision;
        this.nextFrameIndex = nextFrameIndex;
        this.frameTimeNanos = frameTimeNanos;
        this.restartRequested = restartRequested;
    }

    public static TemporalReconstructionState initial() {
        return new TemporalReconstructionState(
                null, Long.MIN_VALUE, 0, 0L, true);
    }

    public TemporalReconstructionState invalidated() {
        return this.restartRequested
                ? this
                : new TemporalReconstructionState(
                        this.camera,
                        this.sceneRevision,
                        this.nextFrameIndex,
                        this.frameTimeNanos,
                        true);
    }

    public Plan plan(Input input) {
        Objects.requireNonNull(input, "input");
        boolean initialized = this.camera != null;
        boolean cameraCut = initialized
                && CameraDiscontinuity.isCut(this.camera, input.camera());
        boolean restart = this.restartRequested
                || input.forceRestart()
                || !initialized
                || cameraCut
                || input.sceneRevision() != this.sceneRevision;
        int currentFrameIndex = restart ? 0 : this.nextFrameIndex;
        float deltaMilliseconds = FrameTime.deltaMilliseconds(
                initialized, input.frameTimeNanos(), this.frameTimeNanos);
        TemporalReconstructionState committed = new TemporalReconstructionState(
                input.camera(),
                input.sceneRevision(),
                currentFrameIndex + 1,
                input.frameTimeNanos(),
                false);
        return new Plan(
                input.camera(),
                restart ? input.camera() : this.camera,
                currentFrameIndex,
                restart,
                cameraCut,
                deltaMilliseconds,
                committed);
    }

    public record Input(
            FrameCamera camera,
            long frameTimeNanos,
            long sceneRevision,
            boolean forceRestart) {
        public Input {
            Objects.requireNonNull(camera, "camera");
        }
    }

    public record Plan(
            FrameCamera camera,
            FrameCamera historyCamera,
            int frameIndex,
            boolean restart,
            boolean cameraCut,
            float deltaMilliseconds,
            TemporalReconstructionState committedState) {
        public Plan {
            Objects.requireNonNull(camera, "camera");
            Objects.requireNonNull(historyCamera, "historyCamera");
            Objects.requireNonNull(committedState, "committedState");
        }
    }
}
