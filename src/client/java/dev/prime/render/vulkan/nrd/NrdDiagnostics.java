package dev.prime.render.vulkan.nrd;

import java.util.Arrays;
import java.util.Optional;

/** NRD diagnostic modes and their native output selectors. */
public final class NrdDiagnostics {
    private NrdDiagnostics() {}

    public enum Mode {
        OFF("off", 0, false, -1),
        OPAQUE("opaque", 1, true, 0),
        RAW_NUMERICAL("raw_numerical", 0, false, 1);

        private final String id;
        private final int outputSelector;
        private final boolean nativeValidation;
        private final int presentSource;

        Mode(String id, int outputSelector, boolean nativeValidation, int presentSource) {
            this.id = id;
            this.outputSelector = outputSelector;
            this.nativeValidation = nativeValidation;
            this.presentSource = presentSource;
        }

        public String id() {
            return this.id;
        }

        public int outputSelector() {
            return this.outputSelector;
        }

        public boolean nativeValidation() {
            return this.nativeValidation;
        }

        public int presentSource() {
            if (this.presentSource < 0) {
                throw new IllegalStateException("Off has no diagnostic source");
            }
            return this.presentSource;
        }

        public static Optional<Mode> findById(String id) {
            if (id == null) {
                return Optional.empty();
            }
            // Migrate the development-only names used before validation became a user feature.
            String canonicalId = switch (id) {
                case "nrd_validation", "reprojection_error", "motion" -> "opaque";
                case "raw_nonfinite" -> "raw_numerical";
                default -> id;
            };
            return Arrays.stream(values())
                    .filter(value -> value.id.equals(canonicalId))
                    .findFirst();
        }

        public static Mode fromId(String id) {
            return findById(id).orElse(OFF);
        }

    }
}
