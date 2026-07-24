package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gpu-shader")
final class RoboCuteClosureGpuTest {
    private static final long SEED = 0x6C97_7245_985E_9892L;
    private static final int INPUT_WORDS = 6;
    private static final int WITNESS_WORDS = 12;
    private static final int CASES_PER_KIND = 8_192;
    private static final int KIND_COUNT = 4;
    private static final int CASE_COUNT = CASES_PER_KIND * KIND_COUNT;

    private static final float[] ROUGHNESSES = {
        0.0F,
        Math.nextDown(0.01F),
        0.01F,
        Math.nextUp(0.01F),
        0.05F,
        0.25F,
        0.5F,
        1.0F
    };
    private static final float[] WEIGHTS = {
        0.0F,
        Math.nextUp(0.0F),
        0.25F,
        0.5F,
        0.75F,
        Math.nextDown(1.0F),
        1.0F
    };
    private static final float[] VIEW_COSINES = {0.1F, 0.25F, 0.5F, 0.9F, 1.0F};
    private static final float[] RANDOM_BOUNDARIES = {
        0.0F, Math.nextUp(0.0F), 0.5F, Math.nextDown(1.0F)
    };
    private static final float[] IORS = {1.0F, 1.333F, 1.5F, 2.4F};
    private static final float[] RAY_DISTANCES = {
        0.0F, Math.nextUp(0.0F), 0.001F, 1.0F, 64.0F, 1_000.0F
    };

    private static ShaderComputeRunner runner;

    @BeforeAll
    static void openRunner() throws IOException, ShaderComputeRunner.UnavailableException {
        try {
            ByteBuffer lut = RoboCuteTestResources.transmissionGgxEnergy();
            runner = RoboCuteTestResources.openRunnerWithTransmissionGgxEnergy(lut);
        } catch (ShaderComputeRunner.UnavailableException | LinkageError exception) {
            if (Boolean.getBoolean("prime.shaderTests.required")) {
                throw new AssertionError(
                        "A Vulkan compute device is required for shader tests", exception);
            }
            Assumptions.assumeTrue(
                    false, "Vulkan shader tests unavailable: " + exception.getMessage());
        }
    }

    @AfterAll
    static void closeRunner() {
        if (runner != null) {
            runner.close();
        }
    }

    @Test
    void reachableClosuresRemainFiniteAndPreserveEventsAndVolumeState()
            throws IOException {
        ByteBuffer input = createCases();
        Path shader = Path.of(
                System.getProperty("prime.test.shaderDirectory"),
                "robocute_closure_properties.comp.spv");
        ShaderPropertyBatch.assertProperties(
                runner,
                shader,
                input,
                CASE_COUNT,
                INPUT_WORDS,
                WITNESS_WORDS,
                SEED);
    }

    private static ByteBuffer createCases() {
        ByteBuffer input = ShaderTestBuffer.inputs(CASE_COUNT, INPUT_WORDS);
        SplittableRandom random = new SplittableRandom(SEED);
        int caseIndex = 0;
        for (int kind = 0; kind < KIND_COUNT; kind++) {
            for (int localCase = 0; localCase < CASES_PER_KIND; localCase++) {
                boolean transmissionRegression = kind == 2 && localCase == 0;
                float cosineMagnitude = transmissionRegression
                        ? 0.5F
                        : VIEW_COSINES[localCase % VIEW_COSINES.length];
                // Do not couple thin-wall state to the low bits that select
                // roughness; every roughness must occur in both material modes.
                boolean thinWalled = !transmissionRegression
                        && (kind == 3
                        || ((localCase / ROUGHNESSES.length) & 1) != 0);
                boolean twoSided = kind == 2 && !thinWalled;
                float cosine = !transmissionRegression
                        && twoSided && (localCase & 1) != 0
                        ? -cosineMagnitude : cosineMagnitude;
                float sine = (float) Math.sqrt(
                        Math.max(0.0, 1.0 - cosine * cosine));
                float phi = (float) (2.0 * Math.PI * random.nextDouble());
                float randomX = transmissionRegression
                        ? 0.5F : sampleValue(localCase, 0, random);
                float randomY = transmissionRegression
                        ? 0.5F : sampleValue(localCase, 1, random);
                float randomZ = transmissionRegression
                        ? 0.0F : sampleValue(localCase, 2, random);
                float colorX = localCase < 8
                        ? ((localCase & 1) == 0 ? 0.0F : 1.0F)
                        : (float) random.nextDouble();
                float colorY = localCase < 8
                        ? ((localCase & 2) == 0 ? 0.0F : 1.0F)
                        : (float) random.nextDouble();
                float colorZ = localCase < 8
                        ? ((localCase & 4) == 0 ? 0.0F : 1.0F)
                        : (float) random.nextDouble();
                float transmissionX = 0.1F + 0.9F * (float) random.nextDouble();
                float transmissionY = 0.1F + 0.9F * (float) random.nextDouble();
                float transmissionZ = 0.1F + 0.9F * (float) random.nextDouble();

                putKind(input, caseIndex, kind);
                putParams(
                        input,
                        caseIndex,
                        transmissionRegression
                                ? 0.25F
                                : ROUGHNESSES[localCase % ROUGHNESSES.length],
                        WEIGHTS[(localCase / ROUGHNESSES.length) % WEIGHTS.length],
                        sine * (float) Math.cos(phi),
                        sine * (float) Math.sin(phi),
                        cosine,
                        randomX,
                        randomY,
                        randomZ,
                        colorX,
                        colorY,
                        colorZ,
                        transmissionRegression
                                ? 1.5F
                                : IORS[(localCase / 7) % IORS.length],
                        thinWalled ? 1.0F : 0.0F,
                        RAY_DISTANCES[localCase % RAY_DISTANCES.length],
                        transmissionRegression ? 0 : localCase % 3,
                        (localCase & 8) == 0 ? 0.0F : 1.0F,
                        transmissionX,
                        transmissionY,
                        transmissionZ,
                        WEIGHTS[(localCase / 13) % WEIGHTS.length],
                        WEIGHTS[(localCase / 17) % WEIGHTS.length],
                        0.0F,
                        1.0F);
                caseIndex++;
            }
        }
        assertEquals(CASE_COUNT, caseIndex, "closure property case count");
        return input;
    }

    private static float sampleValue(
            int caseIndex, int dimension, SplittableRandom random) {
        int boundaryCases = RANDOM_BOUNDARIES.length
                * RANDOM_BOUNDARIES.length
                * RANDOM_BOUNDARIES.length;
        if (caseIndex < boundaryCases) {
            int divisor = 1;
            for (int index = 0; index < dimension; index++) {
                divisor *= RANDOM_BOUNDARIES.length;
            }
            return RANDOM_BOUNDARIES[
                    (caseIndex / divisor) % RANDOM_BOUNDARIES.length];
        }
        return (float) random.nextDouble();
    }

    private static void putKind(ByteBuffer input, int caseIndex, int kind) {
        ShaderTestBuffer.putInt(
                input, caseIndex, INPUT_WORDS, 0, 0, kind);
    }

    private static void putParams(ByteBuffer input, int caseIndex, float... values) {
        if (values.length > INPUT_WORDS * 4 - 1) {
            throw new IllegalArgumentException("Too many closure property parameters");
        }
        for (int parameter = 0; parameter < values.length; parameter++) {
            int flatComponent = parameter + 1;
            ShaderTestBuffer.putFloat(
                    input,
                    caseIndex,
                    INPUT_WORDS,
                    flatComponent / 4,
                    flatComponent % 4,
                    values[parameter]);
        }
    }
}
