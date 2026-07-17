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
        OPAQUE("opaque", 1),
        REFLECTION("reflection", 2),
        TRANSMISSION("transmission", 3);

        private final String id;
        private final int outputSelector;

        Mode(String id, int outputSelector) {
            this.id = id;
            this.outputSelector = outputSelector;
        }

        public String id() {
            return this.id;
        }

        public int outputSelector() {
            return this.outputSelector;
        }

        public static Optional<Mode> findById(String id) {
            if (id == null) {
                return Optional.empty();
            }
            // Migrate the three development-only views used before branch validation became a
            // permanent user feature. All of them inspected the opaque NRD instance.
            String canonicalId = switch (id) {
                case "nrd_validation", "reprojection_error", "motion" -> "opaque";
                default -> id;
            };
            return Arrays.stream(values())
                    .filter(value -> value.id.equals(canonicalId))
                    .findFirst();
        }

        public static Mode fromId(String id) {
            return findById(id).orElse(OFF);
        }

        boolean enablesValidationFor(Mode denoiser) {
            return this != OFF && this == denoiser;
        }
    }
}
