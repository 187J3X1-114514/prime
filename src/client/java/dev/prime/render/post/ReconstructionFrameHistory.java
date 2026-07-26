package dev.prime.render.post;

import java.util.Objects;

/**
 * Single owner of one reconstruction backend's submitted temporal identity.
 *
 * <p>Planning is pure with respect to device work. Exactly one version may be outstanding, it is
 * consumed once by command recording, and only successful submission advances history.
 */
public final class ReconstructionFrameHistory {
    private TemporalReconstructionState state =
            TemporalReconstructionState.initial();
    private PlannedFrame pending;

    public PlannedFrame plan(TemporalReconstructionState.Input input) {
        Objects.requireNonNull(input, "input");
        if (this.pending != null) {
            throw new IllegalStateException(
                    "Previous reconstruction frame has not been submitted");
        }
        TemporalReconstructionState.Plan transition =
                this.state.plan(input);
        PlannedFrame frame = new PlannedFrame(
                this,
                transition,
                transition.committedState());
        this.pending = frame;
        return frame;
    }

    public void requestReset() {
        if (this.pending != null) {
            throw new IllegalStateException(
                    "Cannot reset reconstruction history with an outstanding frame");
        }
        this.state = this.state.invalidated();
    }

    public void submitted(PlannedFrame frame) {
        if (frame == null
                || frame.owner != this
                || frame != this.pending
                || !frame.consumed
                || frame.submitted) {
            throw new IllegalArgumentException(
                    "Reconstruction frame does not belong to this submitted history");
        }
        frame.submitted = true;
        this.state = frame.committedState;
        this.pending = null;
    }

    public void abandon(PlannedFrame frame) {
        if (frame == null
                || frame.owner != this
                || frame != this.pending
                || frame.submitted) {
            throw new IllegalArgumentException(
                    "Reconstruction frame does not belong to this history");
        }
        frame.abandoned = true;
        this.pending = null;
    }

    public static final class PlannedFrame {
        private final ReconstructionFrameHistory owner;
        private final TemporalReconstructionState.Plan plan;
        private final TemporalReconstructionState committedState;
        private boolean consumed;
        private boolean submitted;
        private boolean abandoned;

        private PlannedFrame(
                ReconstructionFrameHistory owner,
                TemporalReconstructionState.Plan plan,
                TemporalReconstructionState committedState) {
            this.owner = owner;
            this.plan = plan;
            this.committedState = committedState;
        }

        public TemporalReconstructionState.Plan plan() {
            return this.plan;
        }

        public TemporalReconstructionState.Plan claimForExecution() {
            if (this.consumed || this.submitted || this.abandoned) {
                throw new IllegalArgumentException(
                        "Reconstruction frame was already consumed");
            }
            this.consumed = true;
            return this.plan;
        }
    }
}
