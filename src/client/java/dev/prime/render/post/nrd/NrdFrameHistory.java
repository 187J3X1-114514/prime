package dev.prime.render.post.nrd;

import dev.prime.render.post.SubmittedFrame;
import dev.prime.render.post.SubmittedFrameHistory;

/**
 * Single owner of NRD's logical temporal history.
 *
 * <p>Planning is device-free. A plan becomes history only after the command buffer that consumed
 * it has been submitted successfully.
 */
public final class NrdFrameHistory {
    private final SubmittedFrameHistory<NrdTemporalState, NrdFrameInput, NrdFramePlan> history =
            new SubmittedFrameHistory<>(NrdTemporalState.initial(), (state, input) -> {
                NrdTemporalState.Plan transition = state.plan(input);
                return new SubmittedFrameHistory.Transition<>(
                        transition.semanticPlan(input), transition.committedState());
            });

    public SubmittedFrame<NrdFramePlan> plan(NrdFrameInput input) {
        return this.history.plan(input);
    }

    public void submitted(SubmittedFrame<NrdFramePlan> frame) {
        this.history.submitted(frame);
    }

    public void abandon(SubmittedFrame<NrdFramePlan> frame) {
        this.history.abandon(frame);
    }
}
