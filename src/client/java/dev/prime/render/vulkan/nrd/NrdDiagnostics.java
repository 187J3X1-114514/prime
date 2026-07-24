package dev.prime.render.vulkan.nrd;

import java.util.Arrays;
import java.util.Optional;

/** NRD diagnostic modes and their native output selectors. */
public final class NrdDiagnostics {
    private NrdDiagnostics() {}

    public enum Mode {
        OFF("off", 0, false, -1, 0),
        NATIVE_VALIDATION("native_validation", 1, true, 0, 0),
        RAW_NUMERICAL("raw_numerical", 0, false, 1, 1),
        RAW_NUMERICAL_STAGE("raw_numerical_stage", 0, false, 1, 2),
        REPROJECTION_ERROR("reprojection_error", 2, false, 2, 0),
        MOTION("motion", 3, false, 2, 0),
        SPECULAR_INPUT("specular_input", 4, false, 2, 0),
        SPECULAR_OUTPUT("specular_output", 5, false, 2, 0),
        SPECULAR_REMODULATED("specular_remodulated", 6, false, 2, 0);

        private final String id;
        private final int outputSelector;
        private final boolean nativeValidation;
        private final int presentSource;
        private final int presentation;

        Mode(
                String id,
                int outputSelector,
                boolean nativeValidation,
                int presentSource,
                int presentation) {
            this.id = id;
            this.outputSelector = outputSelector;
            this.nativeValidation = nativeValidation;
            this.presentSource = presentSource;
            this.presentation = presentation;
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

        public int presentation() {
            if (this.presentSource < 0) {
                throw new IllegalStateException("Off has no diagnostic presentation");
            }
            return this.presentation;
        }

        public boolean rawNumerical() {
            return this == RAW_NUMERICAL || this == RAW_NUMERICAL_STAGE;
        }

        public static Optional<Mode> findById(String id) {
            if (id == null) {
                return Optional.empty();
            }
            // Migrate the development-only names used before validation became a user feature.
            String canonicalId = switch (id) {
                case "opaque", "nrd_validation" -> "native_validation";
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
