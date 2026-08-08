package dev.prime.render.post;

/**
 * Single owner of one reconstruction backend's submitted temporal identity.
 *
 * <p>Planning is pure with respect to device work. Exactly one version may be outstanding, it is
 * consumed once by command recording, and only successful submission advances history.
 */
public final class ReconstructionFrameHistory {
    private final SubmittedFrameHistory<
                    TemporalReconstructionState,
                    TemporalReconstructionState.Input,
                    TemporalReconstructionState.Plan> history =
            new SubmittedFrameHistory<>(
                    TemporalReconstructionState.initial(),
                    (state, input) -> {
                        TemporalReconstructionState.Plan transition = state.plan(input);
                        return new SubmittedFrameHistory.Transition<>(
                                transition, transition.committedState());
                    });

    public SubmittedFrame<TemporalReconstructionState.Plan> plan(
            TemporalReconstructionState.Input input) {
        return this.history.plan(input);
    }

    public void requestReset() {
        this.history.reset(TemporalReconstructionState::invalidated);
    }

    public void submitted(SubmittedFrame<TemporalReconstructionState.Plan> frame) {
        this.history.submitted(frame);
    }

    public void abandon(SubmittedFrame<TemporalReconstructionState.Plan> frame) {
        this.history.abandon(frame);
    }
}
