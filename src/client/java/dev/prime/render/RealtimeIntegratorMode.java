package dev.prime.render;

import java.util.Optional;

/** Selects a current-wavefront realtime transport specialization. */
public enum RealtimeIntegratorMode {
    FULL_WAVEFRONT("wavefront"),
    LIGHTWEIGHT_WAVEFRONT("lightweight_wavefront");

    public static final RealtimeIntegratorMode DEFAULT = FULL_WAVEFRONT;

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

    public static RealtimeIntegratorMode fromId(String id) {
        return findById(id).orElse(DEFAULT);
    }
}
