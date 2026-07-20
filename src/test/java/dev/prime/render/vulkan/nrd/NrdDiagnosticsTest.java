package dev.prime.render.vulkan.nrd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class NrdDiagnosticsTest {
    @Test
    void modesSelectExactlyOneFinalValidationSource() {
        assertArrayEquals(
                new int[] {0, 1},
                Arrays.stream(NrdDiagnostics.Mode.values())
                        .mapToInt(NrdDiagnostics.Mode::outputSelector)
                        .toArray());
        for (NrdDiagnostics.Mode selected : NrdDiagnostics.Mode.values()) {
            for (NrdDiagnostics.Mode denoiser : NrdDiagnostics.Mode.values()) {
                boolean expected = selected != NrdDiagnostics.Mode.OFF && selected == denoiser;
                assertEquals(expected, selected.enablesValidationFor(denoiser));
            }
        }
        assertFalse(NrdDiagnostics.Mode.OFF.enablesValidationFor(NrdDiagnostics.Mode.OPAQUE));
        assertTrue(NrdDiagnostics.Mode.OPAQUE.enablesValidationFor(
                NrdDiagnostics.Mode.OPAQUE));
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
    }
}
