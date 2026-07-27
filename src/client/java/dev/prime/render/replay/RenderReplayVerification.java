package dev.prime.render.replay;

import java.util.Objects;

/** Semantic and same-platform bitwise verdict for two executions of one replay sequence. */
public record RenderReplayVerification(
        RenderReplaySequence reference,
        RenderReplaySequence replay,
        NrdInputSemanticValidator.SequenceReport referenceSemantics,
        NrdInputSemanticValidator.SequenceReport replaySemantics,
        NrdJitterPhaseAnalyzer.Report referenceJitterPhase,
        NrdJitterPhaseAnalyzer.Report replayJitterPhase,
        RenderReplayComparator.Report determinism) {
    public RenderReplayVerification {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(replay, "replay");
        Objects.requireNonNull(referenceSemantics, "referenceSemantics");
        Objects.requireNonNull(replaySemantics, "replaySemantics");
        Objects.requireNonNull(referenceJitterPhase, "referenceJitterPhase");
        Objects.requireNonNull(replayJitterPhase, "replayJitterPhase");
        Objects.requireNonNull(determinism, "determinism");
    }

    public static RenderReplayVerification compare(
            RenderReplaySequence reference, RenderReplaySequence replay) {
        return new RenderReplayVerification(
                reference,
                replay,
                NrdInputSemanticValidator.validate(reference),
                NrdInputSemanticValidator.validate(replay),
                NrdJitterPhaseAnalyzer.analyze(reference),
                NrdJitterPhaseAnalyzer.analyze(replay),
                RenderReplayComparator.compare(reference, replay));
    }

    public boolean valid() {
        return this.referenceSemantics.valid()
                && this.replaySemantics.valid()
                && phaseValid(this.referenceJitterPhase)
                && phaseValid(this.replayJitterPhase)
                && this.determinism.identical();
    }

    public void requireValid() {
        this.referenceSemantics.requireValid();
        this.replaySemantics.requireValid();
        if (this.referenceJitterPhase.measurable()) {
            this.referenceJitterPhase.requireMatched();
        }
        if (this.replayJitterPhase.measurable()) {
            this.replayJitterPhase.requireMatched();
        }
        this.determinism.requireIdentical();
    }

    private static boolean phaseValid(
            NrdJitterPhaseAnalyzer.Report report) {
        return !report.measurable() || report.matched();
    }
}
