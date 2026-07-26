package dev.prime.render.replay;

import java.util.Objects;

/** Semantic and same-platform bitwise verdict for two executions of one replay sequence. */
public record RenderReplayVerification(
        RenderReplaySequence reference,
        RenderReplaySequence replay,
        NrdInputSemanticValidator.SequenceReport referenceSemantics,
        NrdInputSemanticValidator.SequenceReport replaySemantics,
        RenderReplayComparator.Report determinism) {
    public RenderReplayVerification {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(replay, "replay");
        Objects.requireNonNull(referenceSemantics, "referenceSemantics");
        Objects.requireNonNull(replaySemantics, "replaySemantics");
        Objects.requireNonNull(determinism, "determinism");
    }

    public static RenderReplayVerification compare(
            RenderReplaySequence reference, RenderReplaySequence replay) {
        return new RenderReplayVerification(
                reference,
                replay,
                NrdInputSemanticValidator.validate(reference),
                NrdInputSemanticValidator.validate(replay),
                RenderReplayComparator.compare(reference, replay));
    }

    public boolean valid() {
        return this.referenceSemantics.valid()
                && this.replaySemantics.valid()
                && this.determinism.identical();
    }

    public void requireValid() {
        this.referenceSemantics.requireValid();
        this.replaySemantics.requireValid();
        this.determinism.requireIdentical();
    }
}
