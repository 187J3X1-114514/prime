package dev.prime.render;

import java.util.Locale;
import java.util.Optional;

/** Selects the independent realtime light-transport implementation. */
public enum RealtimeIntegratorMode {
    QUALITY("quality"),
    PERFORMANCE("performance");

    public static final RealtimeIntegratorMode DEFAULT = QUALITY;

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
        return switch (normalized) {
            case "quality", "wavefront" -> Optional.of(QUALITY);
            case "performance", "lightweight" -> Optional.of(PERFORMANCE);
            default -> Optional.empty();
        };
    }

    public static RealtimeIntegratorMode fromId(String id) {
        return findById(id).orElse(DEFAULT);
    }
}
