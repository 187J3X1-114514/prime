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
}
