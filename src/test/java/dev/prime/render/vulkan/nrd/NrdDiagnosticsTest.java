package dev.prime.render.vulkan.nrd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class NrdDiagnosticsTest {
    @Test
    void modesKeepTheirShaderAbiAndValidationBoundary() {
        assertArrayEquals(
                new int[] {0, 1, 2, 3},
                Arrays.stream(NrdDiagnostics.Mode.values())
                        .mapToInt(NrdDiagnostics.Mode::shaderValue)
                        .toArray());
        assertFalse(NrdDiagnostics.Mode.OFF.enablesNrdValidation());
        assertTrue(NrdDiagnostics.Mode.NRD_VALIDATION.enablesNrdValidation());
        assertFalse(NrdDiagnostics.Mode.REPROJECTION_ERROR.enablesNrdValidation());
        assertFalse(NrdDiagnostics.Mode.MOTION.enablesNrdValidation());
    }
}
