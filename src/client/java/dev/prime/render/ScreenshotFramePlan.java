package dev.prime.render;

import java.util.Objects;

/** Immutable semantic plan for one native screenshot accumulation sample. */
public record ScreenshotFramePlan(
        ScreenshotFrameInput input,
        IntegratorFrameInput integrator) {
    public ScreenshotFramePlan {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(integrator, "integrator");
    }

    public long nextSampleCount() {
        return Math.incrementExact(this.input.sampleCount());
    }

    public void requireSceneRevision(long revision) {
        if (revision != this.input.sceneRevision()) {
            throw new IllegalStateException(
                    "Screenshot frame plan does not match its resident scene");
        }
    }

    public void requireTextureRevision(long revision) {
        if (revision != this.input.textureRevision()) {
            throw new IllegalStateException(
                    "Screenshot frame plan does not match its texture snapshot");
        }
    }
}
