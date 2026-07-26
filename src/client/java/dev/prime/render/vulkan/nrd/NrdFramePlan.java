package dev.prime.render.vulkan.nrd;

import dev.prime.render.FrameCamera;
import java.util.Objects;

/**
 * Immutable history and input-preparation semantics decided before GPU command recording.
 *
 * <p>The plan contains no native instance, Vulkan handle or mutable backend token.
 */
public record NrdFramePlan(
        NrdFrameInput input,
        FrameCamera historyCamera,
        float historyJitterX,
        float historyJitterY,
        int frameIndex,
        boolean restart,
        float deltaMilliseconds) {
    public NrdFramePlan {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(historyCamera, "historyCamera");
        if (!Float.isFinite(historyJitterX)
                || !Float.isFinite(historyJitterY)
                || !Float.isFinite(deltaMilliseconds)
                || deltaMilliseconds < 0.0F) {
            throw new IllegalArgumentException(
                    "NRD planned temporal values must be finite and non-negative where required");
        }
        if (frameIndex < 0) {
            throw new IllegalArgumentException(
                    "NRD planned frame index must be non-negative");
        }
    }
}
