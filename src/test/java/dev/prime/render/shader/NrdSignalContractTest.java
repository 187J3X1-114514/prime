package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class NrdSignalContractTest {
    @Test
    void primaryMissNeverConsumesSurfaceDenoiserHistory() throws IOException {
        Path shaderRoot = Path.of(System.getProperty("user.dir"), "shaders");
        String rayGeneration = Files.readString(shaderRoot.resolve("world.rgen"));
        String preparation = Files.readString(shaderRoot.resolve("nrd_motion.comp"));
        String composite = Files.readString(shaderRoot.resolve("nrd_composite.comp"));

        assertTrue(rayGeneration.contains(
                "vec4(sampleResult.primaryBaseColor, sampleResult.primaryDistance)"));
        assertTrue(rayGeneration.contains("primeNrdNoisySpecular"));
        assertTrue(rayGeneration.contains("private raygen -> NRD-preparation scratch contract"));
        assertTrue(preparation.contains("primeNrdMaterialFactors("));
        assertTrue(preparation.contains("primeNrdPackRadianceAndHitDistance("));
        assertTrue(preparation.contains("primeNrdPackNormalRoughness("));
        assertTrue(preparation.contains("vec4(diffuseMaterialFactor, primaryDistance)"));
        assertTrue(composite.contains("if (material.a < 0.0)"));
        assertTrue(composite.contains("return vec3(0.0);"));
        assertTrue(composite.contains("primeCompositeSurfaceSignal("));
        assertTrue(composite.contains("primeDenoisedSpecular"));
    }
}
