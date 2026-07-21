package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class IntegratorArchitectureContractTest {
    @Test
    void beautyEstimateAndDenoiserGuidesHaveOneWayOwnership() throws IOException {
        String integrator = shader("integrator.glsl");
        String raygen = shader("world.rgen");
        String screenshot = shader("screenshot.rgen");

        assertTrue(integrator.contains("struct PrimePathRadiance"));
        assertTrue(integrator.contains("struct PrimeDenoiserGuides"));
        assertTrue(integrator.contains("PrimePathRadiance radiance;"));
        assertTrue(integrator.contains("PrimeDenoiserGuides guides;"));
        assertTrue(integrator.contains("vec3 primeResolveIntegrationRadiance"));
        assertFalse(integrator.contains("PrimeTransparencyGuideResult"));
        assertFalse(raygen.contains("denoiser_guides.glsl"));
        assertFalse(screenshot.contains("denoiser_guides.glsl"));
        assertTrue(screenshot.contains("primeResolveIntegrationRadiance(sampleResult)"));
        assertFalse(integrator.contains("primaryScatterEventFlags"));
        assertFalse(integrator.contains("primaryScatterProposalProbability"));
    }

    @Test
    void samplingRetainsRequiredFinitePrecisionNanGuards() throws IOException {
        String bsdf = shader("bsdf.glsl");
        String lights = shader("lights.glsl");

        assertTrue(bsdf.contains("const float PRIME_MINIMUM_BSDF_SAMPLE_PDF = 1.0e-4;"));
        assertTrue(bsdf.contains("max(sampleValue.pdf, PRIME_MINIMUM_BSDF_SAMPLE_PDF)"));
        assertTrue(bsdf.contains("branchProbability <= PRIME_BSDF_EPSILON"));
        assertTrue(bsdf.contains("cosine > PRIME_BSDF_EPSILON"));
        assertTrue(bsdf.contains("cosine <= PRIME_BSDF_EPSILON"));
        assertTrue(bsdf.contains("0 * Inf = NaN"));
        assertTrue(lights.contains("const float PRIME_MAXIMUM_INVERSE_PDF = 1.0e30;"));
        assertTrue(lights.contains("min(1.0 / sampledPdf, PRIME_MAXIMUM_INVERSE_PDF)"));
        assertTrue(lights.contains("intentional finite-precision bias"));
    }

    @Test
    void nrdReceivesStableMaterialClasses() throws IOException {
        String common = shader("nrd_common.glsl");
        String raygen = shader("world.rgen");
        String bridge = Files.readString(Path.of(
                System.getProperty("user.dir"), "native", "nrd", "prime_nrd_bridge.cpp"));

        assertTrue(common.contains("float primeNrdMaterialId(uint materialFlags)"));
        assertTrue(raygen.contains("primeNrdMaterialId(sampleResult.guides.primaryMaterialFlags)"));
        assertTrue(bridge.contains("settings.minMaterialForDiffuse = 0.0f;"));
        assertTrue(bridge.contains("settings.minMaterialForSpecular = 0.0f;"));
        assertTrue(bridge.contains("settings.strandMaterialID = 1.0f;"));
    }

    private static String shader(String name) throws IOException {
        return Files.readString(Path.of(System.getProperty("user.dir"), "shaders", name));
    }
}
