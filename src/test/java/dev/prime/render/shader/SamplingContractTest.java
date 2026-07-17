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
        String camera = Files.readString(shaderRoot.resolve("camera.glsl"));
        String transparent = Files.readString(shaderRoot.resolve("transparent.rgen"));
        String integrator = Files.readString(shaderRoot.resolve("integrator.glsl"));
        String allConsumers = rayGeneration
                + camera
                + transparent
                + integrator
                + Files.readString(shaderRoot.resolve("bsdf.glsl"))
                + Files.readString(shaderRoot.resolve("lights.glsl"));

        assertTrue(sampling.contains("primeSobolSample1D"));
        assertTrue(sampling.contains("primeSobolSample2D"));
        assertTrue(sampling.contains("primeSobolSample3D"));
        assertTrue(sampling.contains("primeSobolSample4D"));
        assertTrue(camera.contains(
                "(primePush.path.z >> 16u) & PRIME_PATH_JITTER_PHASE_MASK"));
        assertFalse(camera.contains("primePush.path.w %"));
        assertTrue(camera.contains("return vec2(halton2, halton3)"));
        assertTrue(rayGeneration.contains("primeCameraPath(pixel, 0u, cameraSample)"));
        assertTrue(transparent.contains("primeCameraPath(pixel, 0u, cameraSample)"));
        assertTrue(sampling.contains("seed = primeHashCombine(seed, base.pathIndex)"));
        assertFalse(camera.contains("cameraSampleBase.pixel"));
        assertFalse(sampling.contains("PRIME_SAMPLE_EFFECT_DIRECT_ENVIRONMENT"));
        assertFalse(integrator.contains("primeEstimateDirectEnvironment"));
        assertFalse(allConsumers.contains("primeSampleEnvironment"));
        assertTrue(integrator.contains("PRIME_SAMPLE_EFFECT_DIRECT_SUN"));
        assertTrue(integrator.contains("PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT"));
        assertTrue(integrator.contains("PRIME_SAMPLE_EFFECT_SCATTER_BSDF"));
        assertTrue(integrator.contains("PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE"));
        assertTrue(integrator.contains(
                "const uint rouletteStart = PRIME_RUSSIAN_ROULETTE_START"));
        assertFalse(integrator.contains("rouletteStart = 5u"));
        assertFalse(allConsumers.contains("primeRandom"));
    }
}
