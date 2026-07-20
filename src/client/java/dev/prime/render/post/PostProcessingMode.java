package dev.prime.render.post;

import java.util.Arrays;
import java.util.Optional;

/** Selects one complete realtime denoising and reconstruction pipeline. */
public enum PostProcessingMode {
    NRD_FSR("nrd_fsr"),
    DLSS_RR("dlss_rr"),
    DISABLED("disabled");

    /** RR is requested by default; the renderer falls back to NRD-FSR when NGX is unavailable. */
    public static final PostProcessingMode DEFAULT = DLSS_RR;

    private final String id;

    PostProcessingMode(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static Optional<PostProcessingMode> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst();
    }

    public static PostProcessingMode fromId(String id) {
        return findById(id).orElse(DEFAULT);
    }
}
