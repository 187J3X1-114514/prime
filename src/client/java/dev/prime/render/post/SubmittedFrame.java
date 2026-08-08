package dev.prime.render.post;

/** One device-free temporal version consumed exactly once by device execution. */
public final class SubmittedFrame<P> {
    private final P plan;
    private State state = State.PLANNED;

    SubmittedFrame(P plan) {
        this.plan = plan;
    }

    public P plan() {
        return this.plan;
    }

    public P claimForExecution() {
        if (this.state != State.PLANNED) {
            throw new IllegalArgumentException("Submitted frame was already consumed");
        }
        this.state = State.CLAIMED;
        return this.plan;
    }

    void submitted() {
        if (this.state != State.CLAIMED) {
            throw new IllegalArgumentException("Submitted frame was not claimed for execution");
        }
        this.state = State.SUBMITTED;
    }

    void abandon() {
        if (this.state == State.SUBMITTED || this.state == State.ABANDONED) {
            throw new IllegalArgumentException("Submitted frame was already completed");
        }
        this.state = State.ABANDONED;
    }

    private enum State {
        PLANNED,
        CLAIMED,
        SUBMITTED,
        ABANDONED
    }
}
