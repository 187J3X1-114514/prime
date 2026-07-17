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
        assertTrue(Files.readString(Path.of("shaders/display_transform.glsl"))
                .contains("* PRIME_DISPLAY_EXPOSURE"));
    }

    @Test
    void signedNativeUpscalerReplacesTheVendoredShaderPipeline() {
        assertTrue(Files.exists(Path.of(
                "src/client/resources/prime/natives/windows-x86_64/amd_fidelityfx_vk.dll")));
        assertFalse(Files.exists(Path.of("shaders/vendor/fidelityfx")));
    }

    @Test
    void semanticMasksDoNotMisclassifyOpaqueOrCutoutTerrain() throws Exception {
        String material = Files.readString(Path.of("shaders/default_material.glsl"));
        String composite = Files.readString(Path.of("shaders/nrd_composite.comp"));
        assertTrue(material.contains("PRIME_MATERIAL_FLAG_ANIMATED_TEXTURE"));
        assertTrue(material.contains("primeFsrTransparencyAndCompositionMask"));
        assertTrue(composite.contains("primeFsrReactiveMask"));
        assertTrue(composite.contains("imageStore(primeFsrReactiveMask"));
        assertTrue(composite.contains("primeFsrTransparencyCompositionMask"));
    }

    @Test
    void rayConeLodAndWeakRcasRemainPartOfTheUpscalingContract() throws Exception {
        String hitCommon = Files.readString(Path.of("shaders/hit_common.glsl"));
        String nativeBinding = Files.readString(Path.of(
                "src/client/java/dev/prime/render/vulkan/fsr/FsrNative.java"));
        assertTrue(hitCommon.contains("float primeRayConeTextureLod"));
        assertTrue(hitCommon.contains("primitive.uvDensity"));
        assertTrue(hitCommon.contains("+ rayCone.y"));
        assertTrue(nativeBinding.contains("description.put(392, (byte) 1)"));
        assertTrue(nativeBinding.contains("FsrSettings.RCAS_SHARPNESS"));
    }

    @Test
    void normalizedUvMotionUsesThePublicHostApiScale() throws Exception {
        String nativeBinding = Files.readString(Path.of(
                "src/client/java/dev/prime/render/vulkan/fsr/FsrNative.java"));
        assertTrue(nativeBinding.contains(
                "(float) dispatch.renderWidth(), (float) dispatch.renderHeight()"));
        assertFalse(nativeBinding.contains("putVector2(description, 368, 1.0F, 1.0F)"));
    }
}
