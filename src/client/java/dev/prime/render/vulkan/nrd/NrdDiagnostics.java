package dev.prime.render.vulkan.nrd;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Persisted runtime control for NRD integration diagnostics. */
public final class NrdDiagnostics {
    private static volatile Mode mode = Mode.OFF;

    private NrdDiagnostics() {}

    public static Mode mode() {
        return mode;
    }

    public static void setMode(Mode value) {
        mode = Objects.requireNonNull(value, "value");
    }

    public enum Mode {
        OFF("off", 0),
        NRD_VALIDATION("nrd_validation", 1),
        REPROJECTION_ERROR("reprojection_error", 2),
        MOTION("motion", 3);

        private final String id;
        private final int shaderValue;

        Mode(String id, int shaderValue) {
            this.id = id;
            this.shaderValue = shaderValue;
        }

        public String id() {
            return this.id;
        }

        public int shaderValue() {
            return this.shaderValue;
        }

        public static Optional<Mode> findById(String id) {
            return Arrays.stream(values())
                    .filter(value -> value.id.equals(id))
                    .findFirst();
        }

        public static Mode fromId(String id) {
            return findById(id).orElse(OFF);
        }

        boolean enablesNrdValidation() {
            return this == NRD_VALIDATION;
        }
    }
}
