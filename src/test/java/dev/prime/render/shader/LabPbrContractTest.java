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
    void authoredBsdfDataUsesRoboCutePolymorphicClosuresAndTheirSamplers() throws IOException {
        String adapter = shader("bsdf.glsl");
        String material = shader("material.glsl");
        String translation = shader("material_translation.glsl");

        for (int metalId = 230; metalId <= 237; metalId++) {
            assertTrue(adapter.contains("metalId == " + metalId + "u"));
        }
        assertTrue(adapter.contains("primeRcF0ToIor(translated.dielectricF0)"));
        assertTrue(adapter.contains("material.weight.subsurface = translated.subsurfaceWeight"));
        assertTrue(adapter.contains("primeRcOpenPbrEvaluate"));
        assertTrue(adapter.contains("primeRcOpenPbrSample"));
        assertTrue(adapter.contains("primeRcBasicMetallicStateInit"));
        assertTrue(adapter.contains("primeRcBasicMetallicEvaluate"));
        assertTrue(adapter.contains("primeRcBasicMetallicSample"));
        assertTrue(adapter.contains("material.weight.subsurface > 0.0"));
        assertTrue(adapter.contains("primeRcTransmissionEvaluate"));
        assertTrue(adapter.contains("primeRcTransmissionSample"));
        assertTrue(material.contains("result.shadingNormal = geometricNormal"));
        assertFalse(material.contains("tangent * decoded.tangentNormal.x"));
        assertTrue(material.contains("primeDecodeAndTranslateLabPbr"));
        assertTrue(translation.contains("metalId >= 230u && metalId <= 237u"));
        assertTrue(translation.contains("metalId == 255u"));
        assertTrue(translation.contains("primeLabPbrIsSupportedMetalId(encoded.metalId)"));
        assertTrue(translation.contains("PRIME_COMMON_DIELECTRIC_F0_MINIMUM = 0.02"));
        assertTrue(translation.contains("PRIME_COMMON_DIELECTRIC_F0_MAXIMUM = 0.17"));
        assertTrue(translation.contains("(flags & PRIME_MATERIAL_FLAG_CUTOUT) != 0u"));
        assertTrue(translation.contains("result.thinWalled = safeThinSubsurface ? 1u : 0u"));
        assertTrue(adapter.contains("primeLabPbrIsCustomMetalId(metalId)"));
        assertTrue(adapter.contains("result.f0 = clamp(baseColor, 0.0, 1.0)"));
        assertTrue(adapter.contains("result.f82Tint = vec3(1.0)"));
        assertTrue(adapter.contains("targetF82 / max(untintedSchlickF82"));
        assertFalse(adapter.contains("result.f82 ="));
    }

    @Test
    void smoothSpecularLayersUseTheSameAnalyticFresnelForEnergyAndSampling()
            throws IOException {
        String closures = shader("robocute_bsdf_closures.glsl");
        String adapter = shader("bsdf.glsl");

        assertTrue(closures.contains("primeRcSmoothSpecularReflectance"));
        assertTrue(closures.contains(
                "if (primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet))"));
        assertTrue(closures.contains(
                "vec3(1.0) - primeRcSmoothSpecularReflectance(wi, state, false)"));
        assertTrue(closures.contains(
                "return primeRcSmoothSpecularReflectance(wi, state, true)"));
        assertTrue(adapter.contains("normalize(reflect(-viewDirection, normal))"));

        double f0 = 0.17;
        double ior = (1.0 + Math.sqrt(f0)) / (1.0 - Math.sqrt(f0));
        for (double cosine : new double[] {1.0, 0.5, 0.1, 0.01, 0.001, 0.0}) {
            double reflection = dielectricFresnel(ior, cosine);
            assertEquals(1.0, reflection + (1.0 - reflection), 0.0);
            assertTrue(reflection >= 0.0 && reflection <= 1.0);
        }
        assertTrue(dielectricFresnel(ior, 0.001) > 0.99);
    }

    @Test
    void roboCuteF82ParameterIsARelativeTintNotAbsoluteReflectance() {
        double cosine82 = 1.0 / 7.0;
        double f0 = 0.04;
        double targetF82 = 0.50;
        double untintedF82 = mix(f0, 1.0, Math.pow(1.0 - cosine82, 5.0));
        double f82Tint = targetF82 / untintedF82;

        assertEquals(
                targetF82,
                schlickF82Tint(f0, f82Tint, cosine82),
                1.0e-12);
        assertTrue(schlickF82Tint(f0, targetF82, cosine82) < targetF82 * 0.75);
        assertEquals(1.0, schlickF82Tint(f0, 1.0, 0.0), 0.0);
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
        String renderer = Files.readString(ROOT.resolve(
                "src/client/java/dev/prime/render/VulkanRenderer.java"));
        String client = Files.readString(ROOT.resolve(
                "src/client/java/dev/prime/PrimeClient.java"));
        String atlas = Files.readString(ROOT.resolve(
                "src/client/java/dev/prime/render/vulkan/LabPbrTextureAtlas.java"));
        String integrator = shader("integrator.glsl");
        String motion = shader("nrd_motion.comp");

        assertEquals(36, ShaderAbi.DESCRIPTOR_LABPBR_NORMAL_ATLAS);
        assertEquals(37, ShaderAbi.DESCRIPTOR_LABPBR_SPECULAR_ATLAS);
        assertTrue(pipeline.contains("ShaderAbi.DESCRIPTOR_LABPBR_NORMAL_ATLAS"));
        assertTrue(pipeline.contains("ShaderAbi.DESCRIPTOR_LABPBR_SPECULAR_ATLAS"));
        assertTrue(runtime.contains("activeRenderer.requestResourceReload()"));
        assertTrue(client.contains("beginResourceReload()"));
        assertTrue(client.contains("finishResourceReload()"));
        assertEquals(1, occurrences(renderer, "this.labPbrAtlas.ensure("));
        assertTrue(atlas.contains("AtomicLong requestedGeneration"));
        assertTrue(atlas.contains("state.animationInfo.frames"));
        assertFalse(atlas.contains("AnimationMetadataSection"));
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

    private static int occurrences(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }

    private static double schlickF82Tint(double f0, double f82Tint, double cosine) {
        double cosineMaximum = 1.0 / 7.0;
        double factor = 1.0
                / (cosineMaximum * Math.pow(1.0 - cosineMaximum, 6.0));
        double a = mix(f0, 1.0, Math.pow(1.0 - cosineMaximum, 5.0))
                * (1.0 - f82Tint)
                * factor;
        return Math.clamp(
                mix(f0, 1.0, Math.pow(1.0 - cosine, 5.0))
                        - a * cosine * Math.pow(1.0 - cosine, 6.0),
                0.0,
                1.0);
    }

    private static double mix(double first, double second, double weight) {
        return first * (1.0 - weight) + second * weight;
    }

    private static double dielectricFresnel(double ior, double cosine) {
        double transmittedCosine = Math.sqrt(
                Math.max(0.0, 1.0 - (1.0 - cosine * cosine) / (ior * ior)));
        double s = (cosine - ior * transmittedCosine)
                / (cosine + ior * transmittedCosine);
        double p = (ior * cosine - transmittedCosine)
                / (ior * cosine + transmittedCosine);
        return 0.5 * (s * s + p * p);
    }
}
