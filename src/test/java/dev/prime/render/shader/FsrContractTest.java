package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class FsrContractTest {
    @Test
    void displayTransformRemainsAfterLinearHdrUpscaling() throws Exception {
        String composite = Files.readString(Path.of("shaders/nrd_composite.comp"));
        String display = Files.readString(Path.of("shaders/fsr_display.comp"));
        assertTrue(composite.contains("rgba16f) uniform writeonly image2D primeCompositeOutput"));
        assertFalse(composite.contains("primeDisplayTransformToSrgb"));
        assertTrue(display.contains("primeDisplayTransformToSrgb"));
    }

    @Test
    void vendoredShaderSetContainsUpscalingButNoFrameInterpolation() throws Exception {
        Path passes = Path.of("shaders/vendor/fidelityfx/passes");
        long passCount;
        try (var files = Files.list(passes)) {
            passCount = files.filter(path -> path.getFileName().toString().endsWith("_pass.glsl"))
                    .count();
        }
        assertTrue(passCount == 8);
        assertTrue(Files.exists(passes.resolve("ffx_fsr3upscaler_rcas_pass.glsl")));
        try (var files = Files.walk(Path.of("shaders/vendor/fidelityfx"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString()
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("frameinterpolation")));
        }
    }

    @Test
    void rayConeLodAndWeakRcasRemainPartOfTheUpscalingContract() throws Exception {
        String hitCommon = Files.readString(Path.of("shaders/hit_common.glsl"));
        String build = Files.readString(Path.of("build.gradle"));
        assertTrue(hitCommon.contains("float primeRayConeTextureLod"));
        assertTrue(hitCommon.contains("primitive.uvDensity"));
        assertTrue(hitCommon.contains("+ rayCone.y"));
        assertTrue(build.contains("FFX_FSR3UPSCALER_OPTION_APPLY_SHARPENING=1"));
    }
}
