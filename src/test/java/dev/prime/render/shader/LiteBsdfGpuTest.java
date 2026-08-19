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
final class LiteBsdfGpuTest {
    private static final long SEED = 0x5D93_16E2_B64A_8F07L;
    private static final int INPUT_WORDS = 4;
    private static final int WITNESS_WORDS = 9;
    private static final int KINDS = 6;
    private static final int CASES_PER_KIND = 4_096;
    private static final int CASE_COUNT = KINDS * CASES_PER_KIND;

    private static final float[] ROUGHNESSES = {
        0.0F,
        Math.nextDown(0.01F),
        0.01F,
        Math.nextUp(0.01F),
        0.05F,
        0.2F,
        0.5F,
        1.0F
    };
    private static final float[] IORS = {1.0F, 1.1F, 1.333F, 1.45F, 1.5F, 2.4F};
    private static final float[] UNIT_BOUNDARIES = {
        0.0F, Math.nextUp(0.0F), 0.5F, Math.nextDown(1.0F)
    };
    private static final float[] COSINES = {
        1.0e-4F, 0.01F, 0.1F, 0.35F, 0.65F, 0.9F, 1.0F
    };

    private static ShaderComputeRunner runner;

    @BeforeAll
    static void openRunner() throws ShaderComputeRunner.UnavailableException {
        try {
            runner = ShaderComputeRunner.open();
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
    void lightweightTopologiesRemainFiniteReciprocalAndSampleTheirSupport()
            throws IOException {
        ByteBuffer input = cases();
        Path shader = Path.of(
                System.getProperty("prime.test.slangShaderDirectory"),
                "lite_bsdf_properties.comp.spv");
        ShaderPropertyBatch.assertProperties(
                runner,
                shader,
                input,
                CASE_COUNT,
                INPUT_WORDS,
                WITNESS_WORDS,
                SEED);
    }

    private static ByteBuffer cases() {
        ByteBuffer input = ShaderTestBuffer.inputs(CASE_COUNT, INPUT_WORDS);
        SplittableRandom random = new SplittableRandom(SEED);
        int caseIndex = 0;
        for (int kind = 0; kind < KINDS; kind++) {
            for (int local = 0; local < CASES_PER_KIND; local++) {
                boolean alternate = (local & 1) != 0;
                float wiCosine = COSINES[local % COSINES.length];
                if (kind == 4 && alternate) {
                    wiCosine = -wiCosine;
                }
                float woMagnitude = COSINES[(local * 5 + 2) % COSINES.length];
                boolean transmitDirection;
                if (kind == 2) {
                    transmitDirection = !alternate || (local & 2) != 0;
                } else if (kind >= 3) {
                    transmitDirection = (local & 2) != 0;
                } else {
                    transmitDirection = false;
                }
                float woCosine = transmitDirection
                        ? -Math.copySign(woMagnitude, wiCosine)
                        : Math.copySign(woMagnitude, wiCosine);
                if (kind <= 1) {
                    woCosine = woMagnitude;
                }

                float colorX = local % 31 == 0
                        ? 0.0F : 0.02F + 0.98F * random.nextFloat();
                float colorY = local % 37 == 0
                        ? 0.0F : 0.02F + 0.98F * random.nextFloat();
                float colorZ = local % 41 == 0
                        ? 0.0F : 0.02F + 0.98F * random.nextFloat();
                putInt(input, caseIndex, 0, kind);
                putParams(
                        input,
                        caseIndex,
                        ROUGHNESSES[local % ROUGHNESSES.length],
                        IORS[(local / ROUGHNESSES.length) % IORS.length],
                        wiCosine,
                        angle(random),
                        woCosine,
                        angle(random),
                        sample(local, 0, random),
                        sample(local, 1, random),
                        sample(local, 2, random),
                        colorX,
                        colorY,
                        colorZ,
                        1.0F,
                        0.001F + 16.0F * random.nextFloat(),
                        alternate ? 1.0F : 0.0F);
                caseIndex++;
            }
        }
        assertEquals(CASE_COUNT, caseIndex, "Lite BSDF property case count");
        return input;
    }

    private static float angle(SplittableRandom random) {
        return (float) (2.0 * Math.PI * random.nextDouble());
    }

    private static float sample(
            int caseIndex, int dimension, SplittableRandom random) {
        int boundaryCases = UNIT_BOUNDARIES.length * UNIT_BOUNDARIES.length;
        if (caseIndex < boundaryCases) {
            int divisor = 1;
            for (int index = 0; index < dimension; index++) {
                divisor *= UNIT_BOUNDARIES.length;
            }
            return UNIT_BOUNDARIES[(caseIndex / divisor) % UNIT_BOUNDARIES.length];
        }
        return random.nextFloat();
    }

    private static void putInt(
            ByteBuffer input, int caseIndex, int component, int value) {
        ShaderTestBuffer.putInt(
                input, caseIndex, INPUT_WORDS, 0, component, value);
    }

    private static void putParams(
            ByteBuffer input, int caseIndex, float... values) {
        if (values.length > INPUT_WORDS * 4 - 1) {
            throw new IllegalArgumentException("Too many Lite BSDF property parameters");
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
