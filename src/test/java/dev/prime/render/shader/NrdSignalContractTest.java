package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class NrdSignalContractTest {
    @Test
    void realtimeUsesOneCompletePathAndOneDenoiserHistory() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        Path shaderRoot = root.resolve("shaders");
        String world = Files.readString(shaderRoot.resolve("world.rgen"));
        String integrator = Files.readString(shaderRoot.resolve("integrator.glsl"));
        String resources = Files.readString(root.resolve(
                "src/client/java/dev/prime/render/RealtimeRenderResources.java"));
        String renderer = Files.readString(root.resolve(
                "src/client/java/dev/prime/render/VulkanRenderer.java"));

        assertTrue(world.contains("PrimeIntegrationResult sampleResult = primeIntegrate(path, integrator)"));
        assertFalse(world.contains("PRIME_OPAQUE_PRIMARY_PASS"));
        assertTrue(integrator.contains("bool hitted_non_delta = false"));
        assertTrue(integrator.contains(
                "vec3 specular_albedo = vec3(1.0)"));
        assertTrue(integrator.contains("if (primeIsNonDeltaSample(bsdf))"));
        assertTrue(integrator.contains("hitted_non_delta = true"));
        assertTrue(integrator.contains("specular_albedo *= albedoSum"));
        assertTrue(integrator.contains("else if (primeIsDeltaSample(bsdf))"));
        assertTrue(integrator.contains("specular_albedo *= surfaceSpecularAlbedo"));
        assertFalse(integrator.contains("path.throughput * albedoSum"));
        assertTrue(integrator.contains(
                "result.primaryNormal = primeSurfaceShadingNormal(surface, viewDirection)"));
        assertTrue(integrator.contains(
                "result.primaryLinearRoughness = primeSurfaceLinearRoughness(surface)"));
        assertFalse(integrator.contains("PrimeContinuationResult"));
        assertFalse(integrator.contains("PrimeDeltaChain"));
        assertFalse(resources.contains("reflectionDenoiser"));
        assertFalse(resources.contains("transmissionDenoiser"));
        assertFalse(resources.contains("NrdTransparentComposite"));
        assertFalse(renderer.contains("traceTransparent"));
        assertFalse(renderer.contains("recordBranch"));
    }

    @Test
    void raygenAndPreparationPreserveTheSingleNrdSignalContract() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        Path shaderRoot = root.resolve("shaders");
        String world = Files.readString(shaderRoot.resolve("world.rgen"));
        String preparation = Files.readString(shaderRoot.resolve("nrd_motion.comp"));
        String composite = Files.readString(shaderRoot.resolve("nrd_composite.comp"));

        assertTrue(world.contains("primeNrdSanitizeRadiance(sampleResult.diffuseRadiance)"));
        assertTrue(world.contains("primeNrdSanitizeRadiance(sampleResult.specularRadiance)"));
        assertTrue(world.contains("sampleResult.primaryPosition"));
        assertTrue(preparation.contains("primeNrdMaterialFactors("));
        assertTrue(preparation.contains("primeNrdPackRadianceAndHitDistance("));
        assertTrue(preparation.contains("primeNrdPackNormalRoughness("));
        assertTrue(composite.contains("primeDenoisedDiffuse"));
        assertTrue(composite.contains("primeDenoisedSpecular"));
        assertFalse(Files.exists(shaderRoot.resolve("transparent.rgen")));
        assertFalse(Files.exists(shaderRoot.resolve("nrd_transparent_motion.comp")));
        assertFalse(Files.exists(shaderRoot.resolve("nrd_transparent_composite.comp")));

        String nativeBridge = Files.readString(
                root.resolve("native/nrd/prime_nrd_bridge.cpp"));
        assertTrue(nativeBridge.contains("HitDistanceReconstructionMode::AREA_5X5"));
        assertFalse(nativeBridge.contains("HitDistanceReconstructionMode::AREA_3X3"));
    }
}
