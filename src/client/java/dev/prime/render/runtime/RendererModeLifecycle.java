package dev.prime.render.runtime;

/** Pure ownership state for the mutually exclusive resolution-sized renderer graphs. */
record RendererModeLifecycle(Mode mode, boolean realtimeSized, boolean offlineSized) {
    enum Mode { REALTIME, OFFLINE }

    RendererModeLifecycle {
        java.util.Objects.requireNonNull(mode, "mode");
        if (realtimeSized && offlineSized) {
            throw new IllegalArgumentException(
                    "Realtime and offline sized resources cannot coexist");
        }
        if (mode == Mode.REALTIME && offlineSized) {
            throw new IllegalArgumentException("Offline resources require offline mode");
        }
        if (mode == Mode.OFFLINE && realtimeSized) {
            throw new IllegalArgumentException("Realtime resources require realtime mode");
        }
    }

    static RendererModeLifecycle initial() {
        return new RendererModeLifecycle(Mode.REALTIME, false, false);
    }

    RendererModeLifecycle allocateRealtimeSized() {
        requireMode(Mode.REALTIME);
        return new RendererModeLifecycle(Mode.REALTIME, true, false);
    }

    RendererModeLifecycle releaseRealtimeSized() {
        requireMode(Mode.REALTIME);
        return new RendererModeLifecycle(Mode.REALTIME, false, false);
    }

    RendererModeLifecycle enterOffline() {
        if (this.mode != Mode.REALTIME || this.realtimeSized) {
            throw new IllegalStateException(
                    "Realtime sized resources must be released before entering offline mode");
        }
        return new RendererModeLifecycle(Mode.OFFLINE, false, false);
    }

    RendererModeLifecycle allocateOfflineSized() {
        requireMode(Mode.OFFLINE);
        return new RendererModeLifecycle(Mode.OFFLINE, false, true);
    }

    RendererModeLifecycle releaseOfflineSized() {
        requireMode(Mode.OFFLINE);
        return new RendererModeLifecycle(Mode.OFFLINE, false, false);
    }

    RendererModeLifecycle exitOffline() {
        if (this.mode != Mode.OFFLINE || this.offlineSized) {
            throw new IllegalStateException(
                    "Offline sized resources must be released before returning to realtime");
        }
        return initial();
    }

    private void requireMode(Mode expected) {
        if (this.mode != expected) {
            throw new IllegalStateException(
                    "Sized resources belong to the " + expected.name().toLowerCase()
                            + " renderer");
        }
    }
}
