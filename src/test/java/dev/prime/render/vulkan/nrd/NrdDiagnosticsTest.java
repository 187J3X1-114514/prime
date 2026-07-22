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
                new int[] {0, 1, 0},
                Arrays.stream(NrdDiagnostics.Mode.values())
                        .mapToInt(NrdDiagnostics.Mode::outputSelector)
                        .toArray());
        assertFalse(NrdDiagnostics.Mode.OFF.nativeValidation());
        assertTrue(NrdDiagnostics.Mode.OPAQUE.nativeValidation());
        assertFalse(NrdDiagnostics.Mode.RAW_NUMERICAL.nativeValidation());
        assertThrows(IllegalStateException.class, NrdDiagnostics.Mode.OFF::presentSource);
        assertEquals(0, NrdDiagnostics.Mode.OPAQUE.presentSource());
        assertEquals(1, NrdDiagnostics.Mode.RAW_NUMERICAL.presentSource());
    }

    @Test
    void oldDevelopmentViewsMigrateToOpaqueValidation() {
        assertEquals(
                NrdDiagnostics.Mode.OPAQUE,
                NrdDiagnostics.Mode.fromId("nrd_validation"));
        assertEquals(
                NrdDiagnostics.Mode.OPAQUE,
                NrdDiagnostics.Mode.fromId("reprojection_error"));
        assertEquals(NrdDiagnostics.Mode.OPAQUE, NrdDiagnostics.Mode.fromId("motion"));
        assertEquals(
                NrdDiagnostics.Mode.RAW_NUMERICAL,
                NrdDiagnostics.Mode.fromId("raw_nonfinite"));
        assertEquals(
                NrdDiagnostics.Mode.RAW_NUMERICAL,
                NrdDiagnostics.Mode.fromId("raw_numerical"));
    }
}
