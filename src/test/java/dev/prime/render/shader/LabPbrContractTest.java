package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class LabPbrContractTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir"));

    @Test
    void allLabPbr13ChannelsAreDecodedWithoutChangingTheirDomains() throws IOException {
        String decoder = shader("labpbr.glsl");

        assertTrue(decoder.contains("sqrt(max(1.0 - dot(normalXY, normalXY), 0.0))"));
        assertTrue(decoder.contains("result.ambientOcclusion = float(normalBytes.z) / 255.0"));
        assertTrue(decoder.contains("result.height = float(normalBytes.w) / 255.0"));
        assertTrue(decoder.contains(
                "result.linearRoughness = result.perceptualRoughness * result.perceptualRoughness"));
        assertTrue(decoder.contains("float(specularBytes.y) / 255.0"));
        assertTrue(decoder.contains("float(specularBytes.z) / 64.0"));
        assertTrue(decoder.contains("float(specularBytes.z - 65u) / 190.0"));
        assertTrue(decoder.contains("primeDecodeLabPbrEmission(specularBytes.w)"));
        assertTrue(decoder.contains("encoded < 255u ? float(encoded) / 254.0 : 0.0"));
    }

    @Test
    void authoredBsdfDataUsesTheCompleteRoboCuteClosureAndItsSampler() throws IOException {
        String adapter = shader("bsdf.glsl");
        String material = shader("material.glsl");

        for (int metalId = 230; metalId <= 237; metalId++) {
            assertTrue(adapter.contains("metalId == " + metalId + "u"));
        }
        assertTrue(adapter.contains("primeRcF0ToIor(decoded.dielectricF0)"));
        assertTrue(adapter.contains("material.weight.subsurface = decoded.subsurface"));
        assertTrue(adapter.contains("primeRcOpenPbrEvaluate"));
        assertTrue(adapter.contains("primeRcOpenPbrSample"));
        assertTrue(adapter.contains("primeRcTransmissionEvaluate"));
        assertTrue(adapter.contains("primeRcTransmissionSample"));
        assertTrue(material.contains("result.shadingNormal = geometricNormal"));
        assertFalse(material.contains("tangent * decoded.tangentNormal.x"));
    }

    @Test
    void authoredEmissionUsesTheExistingAreaLightEstimator() throws IOException {
        String material = shader("material.glsl");
        String lights = Files.readString(ROOT.resolve(
                "src/client/java/dev/prime/render/terrain/CpuSectionLights.java"));
        String distribution = Files.readString(ROOT.resolve(
                "src/client/java/dev/prime/render/terrain/EmissionDistribution.java"));

        assertTrue(material.contains("PRIME_EMITTER_FLAG_LABPBR_EMISSION"));
        assertTrue(material.contains("authoredEmission * PRIME_LEVEL_15_BLOCK_INTENSITY"));
        assertTrue(material.contains("? authoredEmission * PRIME_LEVEL_15_BLOCK_INTENSITY"));
        assertTrue(lights.contains("EMITTER_FLAG_LABPBR_EMISSION"));
        assertTrue(lights.contains("ShaderAbi.LEVEL_15_BLOCK_INTENSITY"));
        assertTrue(distribution.contains("? key.vanillaEmissionFraction"));
        assertTrue(distribution.contains(": authoredEmission"));
    }

    @Test
    void auxiliaryAtlasesAndNrdGuidesShareTheAuthoredMaterial() throws IOException {
        String pipeline = Files.readString(ROOT.resolve(
                "src/client/java/dev/prime/render/vulkan/RayTracingPipeline.java"));
        String runtime = Files.readString(ROOT.resolve(
                "src/client/java/dev/prime/render/RayTracingRuntime.java"));
        String atlas = Files.readString(ROOT.resolve(
                "src/client/java/dev/prime/render/vulkan/LabPbrTextureAtlas.java"));
        String integrator = shader("integrator.glsl");
        String motion = shader("nrd_motion.comp");

        assertEquals(36, ShaderAbi.DESCRIPTOR_LABPBR_NORMAL_ATLAS);
        assertEquals(37, ShaderAbi.DESCRIPTOR_LABPBR_SPECULAR_ATLAS);
        assertTrue(pipeline.contains("ShaderAbi.DESCRIPTOR_LABPBR_NORMAL_ATLAS"));
        assertTrue(pipeline.contains("ShaderAbi.DESCRIPTOR_LABPBR_SPECULAR_ATLAS"));
        assertTrue(runtime.contains("activeRenderer.requestResourceReload()"));
        assertTrue(atlas.contains("NORMAL_DEFAULT_ARGB = 0xff8080ff"));
        assertTrue(atlas.contains("writeArgb(target, offset, defaultArgb)"));
        assertTrue(atlas.contains("SUPPORTED_FORMAT = \"lab-pbr/1.3\""));
        assertTrue(integrator.contains("primeLabPbrLinearRoughness("));
        assertTrue(integrator.contains("primeLabPbrSpecularF0("));
        assertTrue(motion.contains("primeNrdUnpackNormalRoughness("));
        assertTrue(motion.contains("rawSpecularMaterial.rgb"));
    }

    private static String shader(String name) throws IOException {
        return Files.readString(ROOT.resolve("shaders").resolve(name));
    }
}
