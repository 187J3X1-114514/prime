package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class DefaultBsdfEnergyContractTest {
    @Test
    void fallbackAndAuthoredOpaqueMaterialsShareRoboCuteBasicMetallic() throws IOException {
        Path shaderRoot = Path.of(System.getProperty("user.dir"), "shaders");
        String composition = Files.readString(shaderRoot.resolve("bsdf.glsl"));
        String integrator = Files.readString(shaderRoot.resolve("integrator.glsl"));
        String translation = Files.readString(shaderRoot.resolve("material_translation.glsl"));

        assertTrue(translation.contains(": primeDefaultLinearRoughness()"));
        assertTrue(composition.contains("primeRcBasicMetallicStateInit"));
        assertTrue(composition.contains("primeRcBasicMetallicEvaluate"));
        assertTrue(composition.contains("primeRcBasicMetallicSample"));
        assertEquals(
                1,
                Pattern.compile("bsdf = primeSampleOpaque")
                        .matcher(integrator)
                        .results()
                        .count());
        assertEquals(
                1,
                Pattern.compile("PrimePathScatter scatter = primeSamplePathSurface")
                        .matcher(integrator)
                        .results()
                        .count());
        assertTrue(integrator.contains("every ordinary opaque surface"));

        // Missing texture data is a parameter fallback, never permission to select a second
        // energy model. The former fixed-roughness table could not represent the configurable
        // roughness range and over-compensated smooth surfaces.
        assertFalse(composition.contains("PRIME_DEFAULT_GGX_DIRECTIONAL_ENERGY"));
        assertFalse(composition.contains("primeEvaluateDefaultBsdf"));
        assertFalse(composition.contains("primeSampleDefaultBsdf"));
        assertFalse(integrator.contains("PrimeDefaultBsdfContext"));
    }
}
