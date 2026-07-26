package dev.prime.render.vulkan.nrd;

import java.util.Objects;

/**
 * Single owner of NRD's logical temporal history.
 *
 * <p>Planning is device-free. A plan becomes history only after the command buffer that consumed
 * it has been submitted successfully.
 */
public final class NrdFrameHistory {
    private NrdTemporalState state = NrdTemporalState.initial();
    private PlannedFrame pending;

    public PlannedFrame plan(NrdFrameInput input) {
        Objects.requireNonNull(input, "input");
        if (this.pending != null) {
            throw new IllegalStateException(
                    "Previous NRD frame plan has not been submitted");
        }
        NrdTemporalState.Plan transition = this.state.plan(input);
        PlannedFrame frame = new PlannedFrame(
                this,
                transition.semanticPlan(input),
                transition.committedState());
        this.pending = frame;
        return frame;
    }

    public void submitted(PlannedFrame frame) {
        if (frame == null
                || frame.owner != this
                || frame != this.pending
                || !frame.consumed
                || frame.submitted) {
            throw new IllegalArgumentException(
                    "NRD frame plan does not belong to this submitted history");
        }
        frame.submitted = true;
        this.state = frame.committedState;
        this.pending = null;
    }

    /** One device-free temporal version, consumed exactly once by an NRD device execution. */
    public static final class PlannedFrame {
        private final NrdFrameHistory owner;
        private final NrdFramePlan plan;
        private final NrdTemporalState committedState;
        private boolean consumed;
        private boolean submitted;

        private PlannedFrame(
                NrdFrameHistory owner,
                NrdFramePlan plan,
                NrdTemporalState committedState) {
            this.owner = owner;
            this.plan = plan;
            this.committedState = committedState;
        }

        public NrdFramePlan plan() {
            return this.plan;
        }

        NrdFramePlan claimForExecution() {
            if (this.consumed || this.submitted) {
                throw new IllegalArgumentException(
                        "NRD frame plan was already consumed");
            }
            this.consumed = true;
            return this.plan;
        }
    }
}
