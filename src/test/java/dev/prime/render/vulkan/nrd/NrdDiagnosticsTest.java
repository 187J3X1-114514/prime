package dev.prime.render.vulkan.nrd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class NrdDiagnosticsTest {
    @Test
    void modesSelectNativeValidationAndPresentationSources() {
        assertArrayEquals(
                new int[] {0, 1, 0, 0, 0, 2, 3, 4, 5, 6},
                Arrays.stream(NrdDiagnostics.Mode.values())
                        .mapToInt(NrdDiagnostics.Mode::outputSelector)
                        .toArray());
        assertFalse(NrdDiagnostics.Mode.OFF.nativeValidation());
        assertTrue(NrdDiagnostics.Mode.NATIVE_VALIDATION.nativeValidation());
        assertFalse(NrdDiagnostics.Mode.RAW_NUMERICAL.nativeValidation());
        assertFalse(NrdDiagnostics.Mode.RAW_NUMERICAL_STAGE.nativeValidation());
        assertFalse(NrdDiagnostics.Mode.RAW_NUMERICAL_FIELD.nativeValidation());
        assertFalse(NrdDiagnostics.Mode.REPROJECTION_ERROR.nativeValidation());
        assertFalse(NrdDiagnostics.Mode.MOTION.nativeValidation());
        assertFalse(NrdDiagnostics.Mode.SPECULAR_INPUT.nativeValidation());
        assertFalse(NrdDiagnostics.Mode.SPECULAR_OUTPUT.nativeValidation());
        assertFalse(NrdDiagnostics.Mode.SPECULAR_REMODULATED.nativeValidation());
        assertThrows(IllegalStateException.class, NrdDiagnostics.Mode.OFF::presentSource);
        assertThrows(IllegalStateException.class, NrdDiagnostics.Mode.OFF::presentation);
        assertEquals(0, NrdDiagnostics.Mode.NATIVE_VALIDATION.presentSource());
        assertEquals(1, NrdDiagnostics.Mode.RAW_NUMERICAL.presentSource());
        assertEquals(1, NrdDiagnostics.Mode.RAW_NUMERICAL_STAGE.presentSource());
        assertEquals(1, NrdDiagnostics.Mode.RAW_NUMERICAL_FIELD.presentSource());
        assertEquals(2, NrdDiagnostics.Mode.REPROJECTION_ERROR.presentSource());
        assertEquals(2, NrdDiagnostics.Mode.MOTION.presentSource());
        assertEquals(2, NrdDiagnostics.Mode.SPECULAR_INPUT.presentSource());
        assertEquals(2, NrdDiagnostics.Mode.SPECULAR_OUTPUT.presentSource());
        assertEquals(2, NrdDiagnostics.Mode.SPECULAR_REMODULATED.presentSource());
        assertEquals(0, NrdDiagnostics.Mode.NATIVE_VALIDATION.presentation());
        assertEquals(1, NrdDiagnostics.Mode.RAW_NUMERICAL.presentation());
        assertEquals(2, NrdDiagnostics.Mode.RAW_NUMERICAL_STAGE.presentation());
        assertEquals(3, NrdDiagnostics.Mode.RAW_NUMERICAL_FIELD.presentation());
        for (NrdDiagnostics.Mode mode : NrdDiagnostics.Mode.values()) {
            if (mode != NrdDiagnostics.Mode.OFF) {
                assertTrue(mode.presentation() <= NrdDiagnostics.MAX_PRESENTATION);
            }
        }
        assertTrue(NrdDiagnostics.Mode.RAW_NUMERICAL.rawNumerical());
        assertTrue(NrdDiagnostics.Mode.RAW_NUMERICAL_STAGE.rawNumerical());
        assertTrue(NrdDiagnostics.Mode.RAW_NUMERICAL_FIELD.rawNumerical());
        assertFalse(NrdDiagnostics.Mode.REPROJECTION_ERROR.rawNumerical());
        for (NrdDiagnostics.Mode mode : NrdDiagnostics.Mode.values()) {
            assertEquals(mode, NrdDiagnostics.Mode.fromId(mode.id()));
            assertTrue(
                    mode.outputSelector()
                            <= NrdDiagnostics.MAX_OUTPUT_SELECTOR);
        }
    }

    @Test
    void oldDevelopmentViewsAndCurrentDiagnosticsResolveIndependently() {
        assertEquals(
                NrdDiagnostics.Mode.NATIVE_VALIDATION,
                NrdDiagnostics.Mode.fromId("nrd_validation"));
        assertEquals(
                NrdDiagnostics.Mode.REPROJECTION_ERROR,
                NrdDiagnostics.Mode.fromId("reprojection_error"));
        assertEquals(
                NrdDiagnostics.Mode.NATIVE_VALIDATION,
                NrdDiagnostics.Mode.fromId("opaque"));
        assertEquals(
                NrdDiagnostics.Mode.MOTION,
                NrdDiagnostics.Mode.fromId("motion"));
        assertEquals(
                NrdDiagnostics.Mode.RAW_NUMERICAL,
                NrdDiagnostics.Mode.fromId("raw_nonfinite"));
        assertEquals(
                NrdDiagnostics.Mode.RAW_NUMERICAL,
                NrdDiagnostics.Mode.fromId("raw_numerical"));
    }
}
