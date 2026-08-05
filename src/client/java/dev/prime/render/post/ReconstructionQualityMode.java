package dev.prime.render.post;

import java.util.Arrays;
import java.util.Optional;

/** Stable product quality identity; backend-specific policy lives in backend profiles. */
public enum ReconstructionQualityMode {
    NATIVE_AA("native_aa"),
    QUALITY("quality"),
    BALANCED("balanced"),
    PERFORMANCE("performance"),
    ULTRA_PERFORMANCE("ultra_performance");

    public static final ReconstructionQualityMode DEFAULT = PERFORMANCE;

    private final String id;

    ReconstructionQualityMode(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static Optional<ReconstructionQualityMode> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst();
    }

    public static ReconstructionQualityMode fromId(String id) {
        return findById(id).orElse(DEFAULT);
    }
}
