package dev.prime.render.post;

import dev.prime.render.CameraDiscontinuity;
import dev.prime.render.FrameCamera;
import java.util.Objects;

/**
 * Pure temporal identity transition shared by reconstruction backends.
 *
 * <p>{@link Plan#committedState()} becomes current only after the command buffer is submitted.
 * Planning or failed recording therefore cannot consume a history frame.
 */
public final class TemporalReconstructionState {
    private static final float DEFAULT_DELTA_MILLISECONDS = 1000.0F / 60.0F;

    private final FrameCamera camera;
    private final long sceneRevision;
    private final long textureRevision;
    private final int nextFrameIndex;
    private final long frameTimeNanos;
    private final boolean restartRequested;

    private TemporalReconstructionState(
            FrameCamera camera,
            long sceneRevision,
            long textureRevision,
            int nextFrameIndex,
            long frameTimeNanos,
            boolean restartRequested) {
        this.camera = camera;
        this.sceneRevision = sceneRevision;
        this.textureRevision = textureRevision;
        this.nextFrameIndex = nextFrameIndex;
        this.frameTimeNanos = frameTimeNanos;
        this.restartRequested = restartRequested;
    }

    public static TemporalReconstructionState initial() {
        return new TemporalReconstructionState(
                null, Long.MIN_VALUE, Long.MIN_VALUE, 0, 0L, true);
    }

    public TemporalReconstructionState invalidated() {
        return this.restartRequested
                ? this
                : new TemporalReconstructionState(
                        this.camera,
                        this.sceneRevision,
                        this.textureRevision,
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
                || input.sceneRevision() != this.sceneRevision
                || input.textureRevision() != this.textureRevision;
        int currentFrameIndex = restart ? 0 : this.nextFrameIndex;
        float deltaMilliseconds = !initialized
                ? DEFAULT_DELTA_MILLISECONDS
                : Math.min(
                        (input.frameTimeNanos() - this.frameTimeNanos) * 1.0e-6F,
                        1000.0F);
        TemporalReconstructionState committed = new TemporalReconstructionState(
                input.camera(),
                input.sceneRevision(),
                input.textureRevision(),
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
            long textureRevision,
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
