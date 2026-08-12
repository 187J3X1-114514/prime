package dev.prime.render;

import java.util.Optional;

/** Selects the realtime transport scheduling backend. */
public enum RealtimeIntegratorMode {
    WAVEFRONT("wavefront"),
    MEGAKERNEL("megakernel");

    public static final RealtimeIntegratorMode DEFAULT = WAVEFRONT;

    private final String id;

    RealtimeIntegratorMode(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static Optional<RealtimeIntegratorMode> findById(String id) {
        return StableIds.find(values(), id, RealtimeIntegratorMode::id);
    }
}
