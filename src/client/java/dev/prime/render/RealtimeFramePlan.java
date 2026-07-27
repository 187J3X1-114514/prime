package dev.prime.render;

import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.post.RealtimePostProcessor;
import java.util.Objects;

/**
 * Immutable semantic plan shared by wavefront integration and temporal reconstruction.
 *
 * <p>The backend token is copied into plain values and remains outside this type. A plan can
 * therefore be compared or serialized without device ownership becoming part of frame identity.
 */
public record RealtimeFramePlan(
        IntegratorFrameInput integrator,
        RealtimePostProcessor.FrameParameters reconstruction,
        long residentSceneRevision,
        int reconstructionFrameIndex,
        FsrSettings.Jitter jitter,
        boolean reconstructionReset) {
    public RealtimeFramePlan {
        Objects.requireNonNull(integrator, "integrator");
        Objects.requireNonNull(reconstruction, "reconstruction");
        Objects.requireNonNull(jitter, "jitter");
        if (reconstructionFrameIndex < 0) {
            throw new IllegalArgumentException(
                    "Reconstruction frame index must be non-negative");
        }
        if (residentSceneRevision < 0L) {
            throw new IllegalArgumentException(
                    "Realtime resident scene revision must be non-negative");
        }
        if (!Float.isFinite(jitter.x()) || !Float.isFinite(jitter.y())) {
            throw new IllegalArgumentException(
                    "Reconstruction jitter must be finite");
        }
    }

    static RealtimeFramePlan complete(
            RealtimeFrameInput input,
            RealtimeSampleState.Plan sample,
            RealtimePostProcessor.FrameParameters reconstruction,
            RealtimePostProcessor.Frame reconstructionFrame) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(sample, "sample");
        input.requireReconstructionInput(reconstruction, sample.reset());
        Objects.requireNonNull(reconstructionFrame, "reconstructionFrame");
        if (sample.reset() && !reconstructionFrame.reset()) {
            throw new IllegalStateException(
                    "Reconstruction history must restart with the integrator sample sequence");
        }
        FsrSettings.Jitter expected =
                input.expectedJitter(reconstructionFrame.frameIndex());
        FsrSettings.Jitter actual = reconstructionFrame.jitter();
        if (Float.floatToRawIntBits(actual.x())
                        != Float.floatToRawIntBits(expected.x())
                || Float.floatToRawIntBits(actual.y())
                        != Float.floatToRawIntBits(expected.y())) {
            throw new IllegalStateException(
                    "Reconstruction jitter does not match the frame semantic input");
        }
        return new RealtimeFramePlan(
                input.integratorInput(
                        sample.sampleIndex(),
                        sample.epoch(),
                        reconstructionFrame.frameIndex()),
                reconstruction,
                input.residentSceneRevision(),
                reconstructionFrame.frameIndex(),
                actual,
                reconstructionFrame.reset());
    }

    public void requireSceneRevision(long revision) {
        if (revision != this.residentSceneRevision) {
            throw new IllegalStateException(
                    "Realtime frame plan does not match its resident scene");
        }
    }

    public void requireTextureRevision(long revision) {
        if (revision != this.reconstruction.textureRevision()) {
            throw new IllegalStateException(
                    "Realtime frame plan does not match its texture snapshot");
        }
    }
}
