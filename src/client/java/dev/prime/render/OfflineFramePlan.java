package dev.prime.render;

import java.util.Objects;

/** Immutable semantic plan for one native offline accumulation sample. */
public record OfflineFramePlan(
        OfflineFrameInput input,
        IntegratorFrameInput integrator) {
    public OfflineFramePlan {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(integrator, "integrator");
    }

    public long nextSampleCount() {
        return Math.incrementExact(this.input.sampleCount());
    }

    public void requireSceneRevision(long revision) {
        if (revision != this.input.sceneRevision()) {
            throw new IllegalStateException(
                    "Offline frame plan does not match its resident scene");
        }
    }

    public void requireTextureRevision(long revision) {
        if (revision != this.input.textureRevision()) {
            throw new IllegalStateException(
                    "Offline frame plan does not match its texture snapshot");
        }
    }
}
