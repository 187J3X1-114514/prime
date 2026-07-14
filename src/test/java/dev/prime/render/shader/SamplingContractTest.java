package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class SamplingContractTest {
    @Test
    void shadersUseGroupedEffectStreamsInsteadOfOrderDependentRandomState() throws IOException {
        Path shaderRoot = Path.of(System.getProperty("user.dir"), "shaders");
        String sampling = Files.readString(shaderRoot.resolve("sampling.glsl"));
        String rayGeneration = Files.readString(shaderRoot.resolve("world.rgen"));
        String integrator = Files.readString(shaderRoot.resolve("integrator.glsl"));
        String allConsumers = rayGeneration
                + integrator
                + Files.readString(shaderRoot.resolve("bsdf.glsl"))
                + Files.readString(shaderRoot.resolve("lights.glsl"));

        assertTrue(sampling.contains("primeSobolSample1D"));
        assertTrue(sampling.contains("primeSobolSample2D"));
        assertTrue(sampling.contains("primeSobolSample3D"));
        assertTrue(sampling.contains("primeSobolSample4D"));
        assertTrue(rayGeneration.contains("PRIME_SAMPLE_EFFECT_CAMERA"));
        assertTrue(integrator.contains("PRIME_SAMPLE_EFFECT_DIRECT_ENVIRONMENT"));
        assertTrue(integrator.contains("PRIME_SAMPLE_EFFECT_DIRECT_SUN"));
        assertTrue(integrator.contains("PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT"));
        assertTrue(integrator.contains("PRIME_SAMPLE_EFFECT_SCATTER_BSDF"));
        assertTrue(integrator.contains("PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE"));
        assertFalse(allConsumers.contains("primeRandom"));
    }
}
