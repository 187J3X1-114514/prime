package dev.prime.render.replay;

import java.util.List;
import java.util.Objects;

/** Ordered real-render observations sharing one deterministic temporal history. */
public record RenderReplaySequence(List<RenderReplayCapture> frames) {
    public RenderReplaySequence {
        Objects.requireNonNull(frames, "frames");
        frames = List.copyOf(frames);
        if (frames.isEmpty()) {
            throw new IllegalArgumentException(
                    "Render replay sequence must contain at least one frame");
        }
        RenderReplayCapture first = frames.getFirst();
        for (int index = 1; index < frames.size(); index++) {
            RenderReplayCapture frame = frames.get(index);
            if (!first.platform().isStrictlyCompatibleWith(frame.platform())) {
                throw new IllegalArgumentException(
                        "Render replay sequence crosses platform identities");
            }
            if (!first.binary().isStrictlyCompatibleWith(frame.binary())) {
                throw new IllegalArgumentException(
                        "Render replay sequence crosses executable identities");
            }
            if (frame.frame().width() != first.frame().width()
                    || frame.frame().height() != first.frame().height()) {
                throw new IllegalArgumentException(
                        "Render replay sequence changes its render extent");
            }
        }
    }

    public RenderPlatformFingerprint platform() {
        return this.frames.getFirst().platform();
    }

    public int width() {
        return this.frames.getFirst().frame().width();
    }

    public int height() {
        return this.frames.getFirst().frame().height();
    }
}
