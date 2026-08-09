package dev.prime.render;

import dev.prime.render.post.ReconstructionFrame;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.SubpixelJitter;
import java.util.Objects;

/** Immutable backend-neutral plan shared by integration and temporal reconstruction. */
public record RealtimeFramePlan(
        IntegratorFrameInput integrator,
        ReconstructionFrameParameters reconstruction,
        long residentSceneRevision,
        int reconstructionFrameIndex,
        SubpixelJitter jitter,
        boolean reconstructionReset,
        boolean rendererDiagnostics) {
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
    }

    public static RealtimeFramePlan complete(
            RealtimeFrameInput input,
            RealtimeSampleState.Plan sample,
            ReconstructionFrameParameters reconstruction,
            ReconstructionFrame reconstructionFrame,
            SubpixelJitter expectedJitter,
            int jitterPhase,
            int packedRayCone,
            boolean rawNumericalDiagnostic,
            boolean rendererDiagnostics) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(sample, "sample");
        input.requireReconstructionInput(reconstruction, sample.reset());
        Objects.requireNonNull(reconstructionFrame, "reconstructionFrame");
        Objects.requireNonNull(expectedJitter, "expectedJitter");
        if (sample.reset() && !reconstructionFrame.reset()) {
            throw new IllegalStateException(
                    "Reconstruction history must restart with the integrator sample sequence");
        }
        SubpixelJitter actual = reconstructionFrame.jitter();
        if (Float.floatToRawIntBits(actual.x())
                        != Float.floatToRawIntBits(expectedJitter.x())
                || Float.floatToRawIntBits(actual.y())
                        != Float.floatToRawIntBits(expectedJitter.y())) {
            throw new IllegalStateException(
                    "Reconstruction jitter does not match the resolved backend policy");
        }
        return new RealtimeFramePlan(
                input.integratorInput(
                        sample.sampleIndex(),
                        sample.epoch(),
                        jitterPhase,
                        packedRayCone,
                        rawNumericalDiagnostic),
                reconstruction,
                input.residentSceneRevision(),
                reconstructionFrame.frameIndex(),
                actual,
                reconstructionFrame.reset(),
                rendererDiagnostics);
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
