package dev.prime.render.post;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/** Advances temporal state only after the planned device work is submitted successfully. */
public final class SubmittedFrameHistory<S, I, P> {
    private final BiFunction<S, I, Transition<S, P>> planner;
    private S state;
    private SubmittedFrame<P> pending;

    public SubmittedFrameHistory(
            S initialState,
            BiFunction<S, I, Transition<S, P>> planner) {
        this.state = Objects.requireNonNull(initialState, "initialState");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    public SubmittedFrame<P> plan(I input) {
        Objects.requireNonNull(input, "input");
        if (this.pending != null) {
            throw new IllegalStateException("Previous frame has not been submitted");
        }
        Transition<S, P> transition = Objects.requireNonNull(
                this.planner.apply(this.state, input), "transition");
        SubmittedFrame<P> frame = new SubmittedFrame<>(
                this,
                Objects.requireNonNull(transition.plan(), "plan"),
                Objects.requireNonNull(transition.committedState(), "committedState"));
        this.pending = frame;
        return frame;
    }

    public void reset(UnaryOperator<S> invalidation) {
        if (this.pending != null) {
            throw new IllegalStateException("Cannot reset history with an outstanding frame");
        }
        this.state = Objects.requireNonNull(
                invalidation.apply(this.state), "invalidatedState");
    }

    public void submitted(SubmittedFrame<P> frame) {
        if (!owns(frame) || !frame.consumed || frame.submitted) {
            throw new IllegalArgumentException(
                    "Frame does not belong to this submitted history");
        }
        frame.submitted = true;
        this.state = committedState(frame);
        this.pending = null;
    }

    public void abandon(SubmittedFrame<P> frame) {
        if (!owns(frame) || frame.submitted) {
            throw new IllegalArgumentException("Frame does not belong to this history");
        }
        frame.abandoned = true;
        this.pending = null;
    }

    private boolean owns(SubmittedFrame<P> frame) {
        return frame != null && frame.owner == this && frame == this.pending;
    }

    @SuppressWarnings("unchecked")
    private S committedState(SubmittedFrame<P> frame) {
        return (S) frame.committedState;
    }

    public record Transition<S, P>(P plan, S committedState) {
    }
}
