package dev.prime.render;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Selects the independent realtime light-transport implementation. */
public enum RealtimeIntegratorMode {
    WAVEFRONT("wavefront"),
    LIGHTWEIGHT("lightweight");

    public static final RealtimeIntegratorMode DEFAULT = WAVEFRONT;

    private final String id;

    RealtimeIntegratorMode(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static Optional<RealtimeIntegratorMode> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(mode -> mode.id.equals(normalized))
                .findFirst();
    }

    public static RealtimeIntegratorMode fromId(String id) {
        return findById(id).orElse(DEFAULT);
    }
}
