package dev.prime.render;

final class RuntimeStateMachine {
    private RuntimeState current = RuntimeState.UNAVAILABLE;

    RuntimeState current() {
        return this.current;
    }

    void unavailable() {
        if (this.current != RuntimeState.FAILED) {
            this.current = RuntimeState.UNAVAILABLE;
        }
    }

    void rendererReady() {
        if (this.current != RuntimeState.FAILED) {
            this.current = RuntimeState.WAITING_FOR_WORLD;
        }
    }

    void worldAbsent() {
        if (this.current != RuntimeState.FAILED && this.current != RuntimeState.UNAVAILABLE) {
            this.current = RuntimeState.WAITING_FOR_WORLD;
        }
    }

    void worldChanged() {
        this.worldAbsent();
    }

    void worldStreaming(boolean active) {
        if (this.current != RuntimeState.FAILED && this.current != RuntimeState.UNAVAILABLE) {
            if (this.current != RuntimeState.ACTIVE) {
                this.current = active ? RuntimeState.ACTIVE : RuntimeState.STREAMING;
            }
        }
    }

    void fail() {
        this.current = RuntimeState.FAILED;
    }

    void shutdown() {
        this.current = RuntimeState.UNAVAILABLE;
    }
}
