package dev.prime.render.post.nrd;

import dev.prime.render.StableIds;
import java.util.Optional;

/** NRD diagnostic modes and their native output selectors. */
public final class NrdDiagnostics {
    public static final int MAX_PRESENTATION = 3;
    public static final int MAX_OUTPUT_SELECTOR = 6;

    private NrdDiagnostics() {}

    public enum Mode {
        OFF("off", 0, false, -1, 0),
        NATIVE_VALIDATION("native_validation", 1, true, 0, 0),
        RAW_NUMERICAL("raw_numerical", 0, false, 1, 1),
        RAW_NUMERICAL_STAGE("raw_numerical_stage", 0, false, 1, 2),
        RAW_NUMERICAL_FIELD("raw_numerical_field", 0, false, 1, 3),
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
            if (this.presentation < 0 || this.presentation > MAX_PRESENTATION) {
                throw new IllegalStateException(
                        "Unsupported NRD diagnostic presentation " + this.presentation);
            }
            return this.presentation;
        }

        public boolean rawNumerical() {
            return this == RAW_NUMERICAL
                    || this == RAW_NUMERICAL_STAGE
                    || this == RAW_NUMERICAL_FIELD;
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
            return StableIds.find(values(), canonicalId, Mode::id);
        }

        public static Mode fromId(String id) {
            return findById(id).orElse(OFF);
        }

    }
}
