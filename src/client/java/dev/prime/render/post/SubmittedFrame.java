package dev.prime.render.post;

/** One device-free temporal version consumed exactly once by device execution. */
public final class SubmittedFrame<P> {
    final SubmittedFrameHistory<?, ?, P> owner;
    final Object committedState;
    private final P plan;
    boolean consumed;
    boolean submitted;
    boolean abandoned;

    SubmittedFrame(
            SubmittedFrameHistory<?, ?, P> owner,
            P plan,
            Object committedState) {
        this.owner = owner;
        this.plan = plan;
        this.committedState = committedState;
    }

    public P plan() {
        return this.plan;
    }

    public P claimForExecution() {
        if (this.consumed || this.submitted || this.abandoned) {
            throw new IllegalArgumentException("Submitted frame was already consumed");
        }
        this.consumed = true;
        return this.plan;
    }
}
