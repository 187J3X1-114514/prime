package dev.prime.render.post;

import java.util.Objects;

/** Plain semantic projection of a backend-owned frame token. */
public record ReconstructionFrame(
        int frameIndex, SubpixelJitter jitter, boolean reset) {
    public ReconstructionFrame {
        if (frameIndex < 0) {
            throw new IllegalArgumentException(
                    "Reconstruction frame index must be non-negative");
        }
        jitter = Objects.requireNonNull(jitter, "jitter");
    }
}
