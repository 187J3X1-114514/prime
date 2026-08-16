package dev.prime.render.shader;

import dev.prime.render.MaterialSettings;
import dev.prime.render.material.BuiltinMaterialClass;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gpu-shader")
final class PrimeProductionMathGpuTest {
    private static final int CASES_PER_KIND = 8_192;
    private static final long TRANSPORT_SEED = 0x71A4_5A09_D522_0101L;
    private static final long CELESTIAL_SEED = 0x4345_4C45_5354_0001L;
    private static final long MATERIAL_SEED = 0x4A7E_21A1_0000_0001L;
    private static final long NRD_SEED = 0x4E52_4404_1700_0001L;
    private static final long FSR_SEED = 0x4653_5203_0104_0001L;
    private static final long AUTO_EXPOSURE_SEED = 0x4558_504F_5355_5245L;
    private static final long QUEUED_PSR_SEED = 0x5053_5208_0000_0001L;
    private static final long SAMPLING_SEED = 0x5341_4D50_4C49_4E47L;
    private static final long BSDF_CONTRACT_SEED = 0x4253_4446_434F_5245L;
    private static final int[] SPECIAL_FLOAT_BITS = {
        0x0000_0000,
        0x0000_0001,
        0x3f00_0000,
        0x3f80_0000,
        0x7f7f_ffff,
        0x7f80_0000,
        0xff80_0000,
        0x7fc0_0001,
        0xbf80_0000
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
        if (runner != null) runner.close();
    }

    @Test
    void integratorAndLightTransportMathKeepsItsNumericalContracts() throws IOException {
        int kinds = 17;
        int inputWords = 6;
        ShaderPropertyBatch.assertProperties(
                runner,
                slangShader("prime_transport_core_properties.comp.spv"),
                transportCases(kinds, inputWords),
                CASES_PER_KIND * kinds,
                inputWords,
                10,
                TRANSPORT_SEED);
    }

    @Test
    void bsdfAndMediumBoundaryContractsHoldAcrossTheInputDomain() throws IOException {
        int kinds = 4;
        int inputWords = 4;
        int outputWords = 5;
        int cases = CASES_PER_KIND * kinds;
        ByteBuffer input = bsdfContractCases(kinds, inputWords);
        ShaderPropertyBatch.assertProperties(
                runner,
                slangShader("prime_bsdf_contract_properties.comp.spv"),
                input,
                cases,
                inputWords,
                outputWords,
                BSDF_CONTRACT_SEED);

    }

    @Test
    void celestialFramePreservesPolesEquatorialCoordinatesAndDailyRotation()
            throws IOException {
        int kinds = 5;
        int inputWords = 2;
        ShaderPropertyBatch.assertProperties(
                runner,
                slangShader("prime_celestial_properties.comp.spv"),
                celestialCases(kinds, inputWords),
                CASES_PER_KIND * kinds,
                inputWords,
                5,
                CELESTIAL_SEED);
    }

    @Test
    void labPbrDecodeTranslationAndFresnelCoverTheIntegerTransportDomain()
            throws IOException {
        int kinds = 2;
        int inputWords = 3;
        ShaderPropertyBatch.assertProperties(
                runner,
                slangShader("prime_material_properties.comp.spv"),
                materialCases(kinds, inputWords),
                65_536 * kinds,
                inputWords,
                8,
                MATERIAL_SEED);
    }

    @Test
    void nrdPackingDemodulationSanitizationAndReJitterContractsHold() throws IOException {
        int kinds = 10;
        int inputWords = 4;
        ShaderPropertyBatch.assertProperties(
                runner,
                slangShader("prime_nrd_properties.comp.spv"),
                nrdCases(kinds, inputWords),
                CASES_PER_KIND * kinds,
                inputWords,
                9,
                NRD_SEED);
    }

    @Test
    void fsrMasksKeepFoliageLockedAndUseSoftTransparencyHistory()
            throws IOException {
        int cases = 1 << 10;
        int inputWords = 3;
        ByteBuffer input = ShaderTestBuffer.inputs(cases, inputWords);
        for (int flags = 0; flags < cases; flags++) {
            putInt(input, flags, inputWords, 0, 0, 0);
            putInt(input, flags, inputWords, 0, 1, flags);
        }
        ShaderPropertyBatch.assertProperties(
                runner,
                slangShader("prime_fsr_input_properties.comp.spv"),
                input,
                cases,
                inputWords,
                5,
                FSR_SEED);
    }

    @Test
    void fsrDepthAndMotionStayInsideTheDeclaredInputDomains() throws IOException {
        int kinds = 4;
        int inputWords = 3;
        ByteBuffer input = fsrGuideCases(kinds, inputWords);
        ShaderPropertyBatch.assertProperties(
                runner,
                slangShader("prime_fsr_input_properties.comp.spv"),
                input,
                CASES_PER_KIND * kinds,
                inputWords,
                5,
                FSR_SEED);
    }

    @Test
    void exposureAndDisplayCurvesUseTheProductionContract()
            throws IOException {
        int kinds = 9;
        int inputWords = 2;
        ShaderPropertyBatch.assertProperties(
                runner,
                slangShader("prime_auto_exposure_properties.comp.spv"),
                autoExposureCases(kinds, inputWords),
                CASES_PER_KIND * kinds,
                inputWords,
                4,
                AUTO_EXPOSURE_SEED);
    }

    private static ByteBuffer autoExposureCases(int kinds, int inputWords) {
        ByteBuffer input = ShaderTestBuffer.inputs(CASES_PER_KIND * kinds, inputWords);
        SplittableRandom random =
                new SplittableRandom(AUTO_EXPOSURE_SEED);
        for (int kind = 0; kind < kinds; kind++) {
            for (int local = 0; local < CASES_PER_KIND; local++) {
                int index = kind * CASES_PER_KIND + local;
                putInt(input, index, inputWords, 0, 0, kind);
                if (kind == 0 || kind == 4) {
                    float minimum =
                            -16.0F + random.nextFloat() * 36.0F;
                    float maximum = minimum + random.nextFloat()
                            * (20.0F - minimum);
                    float measured = minimum + random.nextFloat()
                            * (maximum - minimum);
                    if ((local & 31) == 0) {
                        maximum = minimum;
                        measured = minimum;
                    }
                    float compensation = switch (local & 3) {
                        case 0 -> 0.0F;
                        case 1 -> 0.5F;
                        case 2 -> 1.0F;
                        default -> random.nextFloat();
                    };
                    putVec4(
                            input,
                            index,
                            inputWords,
                            1,
                            measured,
                            minimum,
                            maximum,
                            compensation);
                } else if (kind == 1) {
                    putVec4(
                            input,
                            index,
                            inputWords,
                            1,
                            random.nextFloat() * 8.0F - 4.0F,
                            random.nextFloat() * 8.0F - 4.0F,
                            0.0F,
                            0.0F);
                } else if (kind == 2) {
                    putVec4(
                            input,
                            index,
                            inputWords,
                            1,
                            random.nextFloat() * 1.25F - 0.125F,
                            random.nextFloat() * 2.0F - 0.5F,
                            0.0F,
                            0.0F);
                } else if (kind == 3) {
                    putVec4(
                            input,
                            index,
                            inputWords,
                            1,
                            random.nextInt(4),
                            random.nextFloat() * 2.0F - 1.0F,
                            0.0F,
                            0.0F);
                } else if (kind == 5) {
                    float red = random.nextFloat() * 64.0F;
                    float green = random.nextFloat() * 64.0F;
                    float blue = random.nextFloat() * 64.0F;
                    if (local < 4) {
                        red = (local & 1) != 0 ? 1.0F : 0.0F;
                        green = local == 3 ? 1.0F : 0.0F;
                        blue = (local & 2) != 0 ? 1.0F : 0.0F;
                    }
                    putVec4(
                            input,
                            index,
                            inputWords,
                            1,
                            red,
                            green,
                            blue,
                            random.nextInt(-12, 13));
                } else if (kind == 6) {
                    float logValue = switch (local & 3) {
                        case 0 -> -12.473931F;
                        case 1 -> -2.473931F;
                        case 2 -> 4.026069F;
                        default -> random.nextFloat() * 24.0F - 16.0F;
                    };
                    putVec4(
                            input,
                            index,
                            inputWords,
                            1,
                            logValue,
                            0.0F,
                            0.0F,
                            0.0F);
                } else {
                    float red = random.nextFloat() * 4.0F;
                    float green = random.nextFloat() * 4.0F;
                    float blue = random.nextFloat() * 4.0F;
                    if (local < 8) {
                        red = (local & 1) != 0 ? 1.0F : 0.0F;
                        green = (local & 2) != 0 ? 1.0F : 0.0F;
                        blue = (local & 4) != 0 ? 1.0F : 0.0F;
                    }
                    putVec4(input, index, inputWords, 1, red, green, blue, 0.0F);
                }
            }
        }
        return input;
    }

    @Test
    void queuedPsrMatchesTheExplicitDeltaChain() throws IOException {
        int inputWords = 21;
        ShaderPropertyBatch.assertProperties(
                runner,
                slangShader("prime_queued_psr_properties.comp.spv"),
                queuedPsrCases(inputWords),
                CASES_PER_KIND,
                inputWords,
                6,
                QUEUED_PSR_SEED);
    }

    private static ByteBuffer bsdfContractCases(int kinds, int words) {
        ByteBuffer input = ShaderTestBuffer.inputs(CASES_PER_KIND * kinds, words);
        SplittableRandom random = new SplittableRandom(BSDF_CONTRACT_SEED);
        for (int kind = 0; kind < kinds; kind++) {
            for (int local = 0; local < CASES_PER_KIND; local++) {
                int index = kind * CASES_PER_KIND + local;
                putInt(input, index, words, 0, 0, kind);
                if (kind == 0) {
                    putInt(input, index, words, 0, 1, random.nextInt());
                } else if (kind == 1) {
                    putInt(input, index, words, 0, 1, local & 7);
                    putInt(input, index, words, 0, 2, random.nextInt());
                } else if (kind == 2) {
                    float first = contractFloat(random, local, 0);
                    float second = contractFloat(random, local, 1);
                    float third = contractFloat(random, local, 2);
                    float pdf = contractFloat(random, local, 3);
                    putVec4(input, index, words, 1, first, second, third, pdf);
                } else {
                    float[] direction = randomUnitVector(random);
                    float red = random.nextFloat() * 8.0F;
                    float green = random.nextFloat() * 8.0F;
                    float blue = random.nextFloat() * 8.0F;
                    float pdf = 0.0001F + random.nextFloat() * 8.0F;
                    float relativeEta = 0.25F + random.nextFloat() * 3.75F;
                    int eventFlags = 1 << (local % 5);
                    switch (local & 7) {
                        case 1 -> direction = new float[] {0.0F, 0.0F, 0.0F};
                        case 2 -> direction[0] *= 2.0F;
                        case 3 -> direction[1] = Float.NaN;
                        case 4 -> pdf = 0.0F;
                        case 5 -> red = -Math.nextUp(0.0F);
                        case 6 -> relativeEta = Float.POSITIVE_INFINITY;
                        case 7 -> eventFlags = 0;
                        default -> {
                        }
                    }
                    putInt(input, index, words, 0, 1, eventFlags);
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            direction[0],
                            direction[1],
                            direction[2],
                            pdf);
                    putVec4(input, index, words, 2, red, green, blue, relativeEta);
                }
            }
        }
        return input;
    }

    private static float contractFloat(
            SplittableRandom random, int local, int component) {
        int special = local + component * 3;
        if (special < SPECIAL_FLOAT_BITS.length) {
            return Float.intBitsToFloat(SPECIAL_FLOAT_BITS[special]);
        }
        return (random.nextFloat() * 2.0F - 0.5F) * powerOfTwo(random.nextInt(-12, 13));
    }

    private static ByteBuffer transportCases(int kinds, int words) {
        ByteBuffer input = ShaderTestBuffer.inputs(CASES_PER_KIND * kinds, words);
        SplittableRandom random = new SplittableRandom(TRANSPORT_SEED);
        for (int kind = 0; kind < kinds; kind++) {
            for (int local = 0; local < CASES_PER_KIND; local++) {
                int index = kind * CASES_PER_KIND + local;
                putInt(input, index, words, 0, 0, kind);
                if (kind == 0) {
                    float denominator = powerOfTwo(random.nextInt(-20, 21));
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            positiveFloat(random, -90, 90),
                            positiveFloat(random, -90, 90),
                            positiveFloat(random, -90, 90),
                            denominator);
                    putVec4(
                            input,
                            index,
                            words,
                            2,
                            positiveFloat(random, -20, 20),
                            positiveFloat(random, -20, 20),
                            positiveFloat(random, -20, 20),
                            0.0F);
                } else if (kind == 1) {
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            positiveFloat(random, -60, 60),
                            positiveFloat(random, -60, 60),
                            powerOfTwo(random.nextInt(-20, 21)),
                            0.0F);
                } else if (kind == 2) {
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            random.nextFloat() * 10.0F,
                            random.nextFloat() * 10.0F,
                            random.nextFloat() * 10.0F,
                            random.nextFloat() * 100.0F);
                } else if (kind == 3) {
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            random.nextFloat(),
                            random.nextFloat(),
                            random.nextFloat(),
                            0.0625F + random.nextFloat() * 15.9375F);
                    putVec4(
                            input,
                            index,
                            words,
                            2,
                            random.nextFloat(),
                            0.0F,
                            0.0F,
                            0.0F);
                } else if (kind == 5) {
                    float opacity = switch (local & 7) {
                        case 0 -> 0.0F;
                        case 1 -> Math.nextUp(0.0F);
                        case 2 -> 0.4F;
                        case 3 -> 0.5F;
                        case 4 -> Math.nextUp(0.5F);
                        case 5 -> 155.0F / 255.0F;
                        case 6 -> 163.0F / 255.0F;
                        default -> 1.0F;
                    };
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            random.nextFloat(),
                            random.nextFloat(),
                            random.nextFloat(),
                            opacity);
                } else if (kind == 6) {
                    boolean sunRay = (local & 3) == 0;
                    float rayDistance = sunRay
                            ? 1_000_000.0F
                            : powerOfTwo(random.nextInt(-10, 13));
                    float boundaryRange = sunRay ? 64.0F : rayDistance;
                    float[] hits = {
                        random.nextFloat() * boundaryRange,
                        random.nextFloat() * boundaryRange,
                        random.nextFloat() * boundaryRange,
                        random.nextFloat() * boundaryRange
                    };
                    Arrays.sort(hits);
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            rayDistance,
                            hits[0],
                            hits[1],
                            hits[2]);
                    putVec4(
                            input,
                            index,
                            words,
                            2,
                            hits[3],
                            random.nextFloat() * 2.0F,
                            random.nextFloat() * 2.0F,
                            random.nextFloat() * 2.0F);
                } else if (kind == 7) {
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            random.nextFloat() * 32.0F,
                            random.nextFloat() * 32.0F,
                            random.nextFloat() * 32.0F,
                            1.0F + random.nextFloat());
                    putVec4(
                            input,
                            index,
                            words,
                            2,
                            random.nextFloat(),
                            random.nextFloat(),
                            random.nextFloat(),
                            random.nextFloat() * 1.98F - 0.99F);
                } else if (kind == 8) {
                    putLightBranchCase(input, index, words, local, random);
                } else if (kind == 10) {
                    float red = random.nextFloat() * 2.0F;
                    float green = random.nextFloat() * 2.0F;
                    float blue = random.nextFloat() * 2.0F;
                    if (local == 0) {
                        red = green = blue = 0.0F;
                    }
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            red,
                            green,
                            blue,
                            powerOfTwo(random.nextInt(-6, 7)));
                    putVec4(
                            input,
                            index,
                            words,
                            2,
                            0.5F + random.nextFloat() * 1.5F,
                            0.0F,
                            0.0F,
                            0.0F);
                } else if (kind == 11) {
                    float[] normal = local == 0
                            ? new float[] {1.0F, 0.0F, 0.0F}
                            : local == 1
                                    ? new float[] {0.0F, 1.0F, 0.0F}
                                    : local == 2
                                            ? new float[] {0.0F, 0.0F, -1.0F}
                                            : randomUnitVector(random);
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            normal[0],
                            normal[1],
                            normal[2],
                            0.0F);
                } else if (kind == 12) {
                    putLightEmissionBoundCase(input, index, words, local, random);
                } else if (kind == 13) {
                    float[] point = {
                        random.nextFloat() * 128.0F - 64.0F,
                        random.nextFloat() * 128.0F - 64.0F,
                        random.nextFloat() * 128.0F - 64.0F
                    };
                    float[] direction = randomUnitVector(random);
                    float distance = 0.25F + random.nextFloat() * 128.0F;
                    float[] receiverNormal = local == 0
                            ? new float[] {0.0F, 0.0F, 0.0F}
                            : randomUnitVector(random);
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            point[0] + direction[0] * distance,
                            point[1] + direction[1] * distance,
                            point[2] + direction[2] * distance,
                            positiveFloat(random, -20, 20));
                    putVec4(
                            input,
                            index,
                            words,
                            2,
                            point[0],
                            point[1],
                            point[2],
                            local == 1 ? 0.0F : positiveFloat(random, -20, 6));
                    putVec4(
                            input,
                            index,
                            words,
                            3,
                            receiverNormal[0],
                            receiverNormal[1],
                            receiverNormal[2],
                            0.0F);
                } else if (kind == 15) {
                    float[] first = local == 0
                            ? new float[] {3.0F, -2.0F, 7.0F}
                            : new float[] {
                                random.nextFloat() * 8192.0F - 4096.0F,
                                random.nextFloat() * 8192.0F - 4096.0F,
                                random.nextFloat() * 8192.0F - 4096.0F
                            };
                    float[] firstEdge = local == 0
                            ? new float[] {0.0F, 0.0F, 0.0F}
                            : randomUnitVector(random);
                    float[] secondEdge = local == 0
                            ? new float[] {0.0F, 0.0F, 0.0F}
                            : randomUnitVector(random);
                    if (local != 0) {
                        float projection = firstEdge[0] * secondEdge[0]
                                + firstEdge[1] * secondEdge[1]
                                + firstEdge[2] * secondEdge[2];
                        secondEdge[0] -= projection * firstEdge[0];
                        secondEdge[1] -= projection * firstEdge[1];
                        secondEdge[2] -= projection * firstEdge[2];
                        float inverseLength = 1.0F / (float) Math.sqrt(
                                secondEdge[0] * secondEdge[0]
                                        + secondEdge[1] * secondEdge[1]
                                        + secondEdge[2] * secondEdge[2]);
                        float firstScale = 0.01F + random.nextFloat() * 128.0F;
                        float secondScale = 0.01F + random.nextFloat() * 128.0F;
                        for (int component = 0; component < 3; component++) {
                            firstEdge[component] *= firstScale;
                            secondEdge[component] *= inverseLength * secondScale;
                        }
                    }
                    float translationX = random.nextFloat() * 8192.0F - 4096.0F;
                    float translationY = random.nextFloat() * 8192.0F - 4096.0F;
                    float translationZ = random.nextFloat() * 8192.0F - 4096.0F;
                    float barycentricX = random.nextFloat();
                    float barycentricY = random.nextFloat() * (1.0F - barycentricX);
                    putVec4(input, index, words, 1,
                            first[0], first[1], first[2], translationX);
                    putVec4(input, index, words, 2,
                            first[0] + firstEdge[0],
                            first[1] + firstEdge[1],
                            first[2] + firstEdge[2],
                            translationY);
                    putVec4(input, index, words, 3,
                            first[0] + secondEdge[0],
                            first[1] + secondEdge[1],
                            first[2] + secondEdge[2],
                            translationZ);
                    putVec4(input, index, words, 4,
                            barycentricX, barycentricY, 0.0F, 0.0F);
                } else if (kind == 16) {
                    int opaquePrimitiveCount = random.nextInt(1, 257);
                    int cutoutPrimitiveCount = random.nextInt(1, 257);
                    int opaqueMacroBase = random.nextInt(opaquePrimitiveCount + 1);
                    int cutoutMacroBase = random.nextInt(cutoutPrimitiveCount + 1);
                    int geometry = local % 3;
                    int opaqueTriangleCount = opaqueMacroBase
                            + 2 * (opaquePrimitiveCount - opaqueMacroBase);
                    int cutoutTriangleCount = cutoutMacroBase
                            + 2 * (cutoutPrimitiveCount - cutoutMacroBase);
                    int localTriangleCount = geometry == 0
                            ? opaqueTriangleCount
                            : geometry == 1
                                    ? cutoutTriangleCount
                                    : random.nextInt(1, 513);
                    int primitiveIndex = random.nextInt(localTriangleCount);
                    int expectedIndex = primitiveIndex
                            + (geometry == 0 ? 0 : opaqueTriangleCount)
                            + (geometry == 2 ? cutoutTriangleCount : 0);
                    putInt(input, index, words, 0, 1, geometry);
                    putInt(input, index, words, 0, 2, primitiveIndex);
                    putInt(input, index, words, 0, 3, expectedIndex);
                    putInt(input, index, words, 1, 0, opaquePrimitiveCount);
                    putInt(input, index, words, 1, 1, cutoutPrimitiveCount);
                    putInt(input, index, words, 1, 2, opaqueMacroBase);
                    putInt(input, index, words, 1, 3, cutoutMacroBase);
                } else {
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            positiveFloat(random, -20, 20),
                            positiveFloat(random, -20, 20),
                            powerOfTwo(random.nextInt(-20, 1)),
                            0.0F);
                }
            }
        }
        return input;
    }

    private static void putLightEmissionBoundCase(
            ByteBuffer input,
            int index,
            int words,
            int local,
            SplittableRandom random) {
        float minX = random.nextFloat() * 16.0F - 8.0F;
        float minY = random.nextFloat() * 16.0F - 8.0F;
        float minZ = random.nextFloat() * 16.0F - 8.0F;
        float maxX = minX + random.nextFloat() * 4.0F;
        float maxY = minY + random.nextFloat() * 4.0F;
        float maxZ = minZ + random.nextFloat() * 4.0F;
        float pointX = minX - 8.0F + random.nextFloat() * 20.0F;
        float pointY = minY - 8.0F + random.nextFloat() * 20.0F;
        float pointZ = minZ - 8.0F + random.nextFloat() * 20.0F;
        float lightX = minX + (maxX - minX) * random.nextFloat();
        float lightY = minY + (maxY - minY) * random.nextFloat();
        float lightZ = minZ + (maxZ - minZ) * random.nextFloat();
        int directionCase = local & 3;
        int axisX = 1023 | 512 << 10 | 4 << 20;
        int packed;
        float normalX;
        float normalY;
        float normalZ;
        float twoSided;
        if (directionCase == 0) {
            packed = axisX;
            normalX = 1.0F;
            normalY = normalZ = 0.0F;
            twoSided = 0.0F;
        } else if (directionCase == 1) {
            packed = axisX | 1 << 30;
            normalX = 1.0F;
            normalY = normalZ = 0.0F;
            twoSided = 1.0F;
        } else if (directionCase == 2) {
            packed = 2 << 30 | 18 | 18 << 10 | 18 << 20;
            float component = 1.0F / (float) Math.sqrt(3.0);
            normalX = normalY = normalZ = component;
            twoSided = 0.0F;
        } else {
            packed = 3 << 30;
            float[] normal = randomUnitVector(random);
            normalX = normal[0];
            normalY = normal[1];
            normalZ = normal[2];
            twoSided = 0.0F;
        }
        putInt(input, index, words, 0, 1, packed);
        putVec4(input, index, words, 1, minX, minY, minZ, 0.0F);
        putVec4(input, index, words, 2, maxX, maxY, maxZ, 0.0F);
        putVec4(input, index, words, 3, pointX, pointY, pointZ, 0.0F);
        putVec4(input, index, words, 4, lightX, lightY, lightZ, 0.0F);
        putVec4(input, index, words, 5, normalX, normalY, normalZ, twoSided);
    }

    private static void putLightBranchCase(
            ByteBuffer input,
            int index,
            int words,
            int local,
            SplittableRandom random) {
        float firstPower = positiveFloat(random, -20, 20);
        float secondPower = positiveFloat(random, -20, 20);
        float firstSoftening = positiveFloat(random, -20, 6);
        float secondSoftening = positiveFloat(random, -20, 6);
        float firstX = random.nextFloat() * 128.0F - 64.0F;
        float firstY = random.nextFloat() * 128.0F - 64.0F;
        float firstZ = random.nextFloat() * 128.0F - 64.0F;
        float secondX = random.nextFloat() * 128.0F - 64.0F;
        float secondY = random.nextFloat() * 128.0F - 64.0F;
        float secondZ = random.nextFloat() * 128.0F - 64.0F;
        float firstExtent = random.nextFloat() * 32.0F;
        float secondExtent = random.nextFloat() * 32.0F;
        float pointX = random.nextFloat() * 256.0F - 128.0F;
        float pointY = random.nextFloat() * 256.0F - 128.0F;
        float pointZ = random.nextFloat() * 256.0F - 128.0F;
        if (local == 0) {
            firstPower = 0.0F;
            secondPower = 0.0F;
            firstSoftening = 0.0F;
            secondSoftening = 0.0F;
        } else if (local == 1) {
            firstPower = 0.0F;
        } else if (local == 2) {
            secondPower = 0.0F;
        } else if (local == 3) {
            firstX = secondX = pointX = 0.0F;
            firstY = secondY = pointY = 0.0F;
            firstZ = secondZ = pointZ = 0.0F;
            firstExtent = secondExtent = 1.0F;
            firstSoftening = secondSoftening = 0.0F;
            firstPower = secondPower = 1.0F;
        } else if (local == 4) {
            firstPower = Float.MAX_VALUE;
            secondPower = Float.MIN_NORMAL;
            pointX = pointY = pointZ = 65_536.0F;
        } else if (local == 6) {
            firstPower = secondPower = 1.0F;
            firstSoftening = secondSoftening = 0.0F;
            firstX = 4.0F;
            firstY = firstZ = -0.5F;
            secondX = -5.0F;
            secondY = secondZ = -0.5F;
            firstExtent = secondExtent = 1.0F;
            pointX = pointY = pointZ = 0.0F;
        }
        float[] receiverNormal = local == 5
                ? new float[] {0.0F, 0.0F, 0.0F}
                : local == 6
                        ? new float[] {1.0F, 0.0F, 0.0F}
                        : randomUnitVector(random);
        putFloat(input, index, words, 0, 1, receiverNormal[0]);
        putFloat(input, index, words, 0, 2, receiverNormal[1]);
        putFloat(input, index, words, 0, 3, receiverNormal[2]);
        putVec4(
                input,
                index,
                words,
                1,
                firstX,
                firstY,
                firstZ,
                firstPower);
        putVec4(
                input,
                index,
                words,
                2,
                firstX + firstExtent,
                firstY + firstExtent,
                firstZ + firstExtent,
                firstSoftening);
        putVec4(
                input,
                index,
                words,
                3,
                secondX,
                secondY,
                secondZ,
                secondPower);
        putVec4(
                input,
                index,
                words,
                4,
                secondX + secondExtent,
                secondY + secondExtent,
                secondZ + secondExtent,
                secondSoftening);
        putVec4(input, index, words, 5, pointX, pointY, pointZ, 0.0F);
    }

    private static ByteBuffer celestialCases(int kinds, int words) {
        ByteBuffer input =
                ShaderTestBuffer.inputs(CASES_PER_KIND * kinds, words);
        SplittableRandom random = new SplittableRandom(CELESTIAL_SEED);
        int[] latitudeBoundaries = {-90, -30, 0, 30, 90};
        int[] longitudeBoundaries = {0, 90, 180, 270, 359};
        float[] hourBoundaries = {
            0.0F,
            (float) (-Math.PI * 0.5),
            (float) (Math.PI * 0.5),
            (float) Math.PI
        };
        for (int kind = 0; kind < kinds; kind++) {
            for (int local = 0; local < CASES_PER_KIND; local++) {
                int index = kind * CASES_PER_KIND + local;
                int latitude = local < latitudeBoundaries.length
                        ? latitudeBoundaries[local]
                        : random.nextInt(-90, 91);
                int solarLongitude = local < longitudeBoundaries.length
                        ? longitudeBoundaries[local]
                        : random.nextInt(360);
                float hourAngle = local < hourBoundaries.length
                        ? hourBoundaries[local]
                        : random.nextFloat() * (float) (Math.PI * 2.0)
                                - (float) Math.PI;
                float rightAscension =
                        random.nextFloat() * (float) (Math.PI * 2.0)
                                - (float) Math.PI;
                float declination =
                        random.nextFloat() * ((float) Math.PI - 2.0e-3F)
                                - ((float) Math.PI * 0.5F - 1.0e-3F);
                putInt(input, index, words, 0, 0, kind);
                putInt(input, index, words, 0, 1, latitude);
                putInt(input, index, words, 0, 2, solarLongitude);
                putVec4(
                        input,
                        index,
                        words,
                        1,
                        hourAngle,
                        rightAscension,
                        declination,
                        0.0F);
            }
        }
        return input;
    }

    private static ByteBuffer materialCases(int kinds, int words) {
        int casesPerKind = 65_536;
        ByteBuffer input = ShaderTestBuffer.inputs(casesPerKind * kinds, words);
        SplittableRandom random = new SplittableRandom(MATERIAL_SEED);
        for (int kind = 0; kind < kinds; kind++) {
            for (int local = 0; local < casesPerKind; local++) {
                int index = kind * casesPerKind + local;
                int flags = local & 0x1ff;
                int normal = kind == 0
                        ? pack(local & 0xff, (local >>> 8) & 0xff, local * 31, local * 67)
                        : pack(local * 13, local * 29, local * 47, local * 71);
                int specular = kind == 1
                        ? pack(local & 0xff, (local >>> 8) & 0xff, local * 43, local * 89)
                        : pack(local * 17, local * 23, local * 53, local * 97);
                putInt(input, index, words, 0, 0, kind);
                putInt(input, index, words, 0, 1, flags);
                putInt(input, index, words, 0, 2, normal);
                putInt(input, index, words, 0, 3, specular);
                putVec4(
                        input,
                        index,
                        words,
                        1,
                        random.nextFloat(),
                        random.nextFloat(),
                        random.nextFloat(),
                        random.nextFloat());
                int builtinId = local & 15;
                float builtinRoughness = builtinId < BuiltinMaterialClass.values().length
                        ? BuiltinMaterialClass.values()[builtinId].roughness()
                        : MaterialSettings.linearRoughness(
                                MaterialSettings.DEFAULT_ROUGHNESS_STEPS);
                if (!Float.isFinite(builtinRoughness)) {
                    builtinRoughness = MaterialSettings.linearRoughness(
                            MaterialSettings.DEFAULT_ROUGHNESS_STEPS);
                }
                int builtinFresnel = builtinId < BuiltinMaterialClass.values().length
                        ? BuiltinMaterialClass.values()[builtinId].fresnelCode()
                        : 0;
                putInt(input, index, words, 2, 0, local & 0xff);
                putInt(input, index, words, 2, 1, builtinId);
                putInt(
                        input,
                        index,
                        words,
                        2,
                        2,
                        Float.floatToRawIntBits(builtinRoughness));
                putInt(input, index, words, 2, 3, builtinFresnel);
            }
        }
        return input;
    }

    private static ByteBuffer nrdCases(int kinds, int words) {
        ByteBuffer input = ShaderTestBuffer.inputs(CASES_PER_KIND * kinds, words);
        SplittableRandom random = new SplittableRandom(NRD_SEED);
        for (int kind = 0; kind < kinds; kind++) {
            for (int local = 0; local < CASES_PER_KIND; local++) {
                int index = kind * CASES_PER_KIND + local;
                putInt(input, index, words, 0, 0, kind);
                putInt(input, index, words, 0, 1, local & 0x3ff);
                if (kind == 6) {
                    putInt(input, index, words, 0, 2, (local >> 1) & 0x7ff);
                    putInt(input, index, words, 0, 3, local & 1);
                }
                if (kind == 1) {
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            random.nextFloat() * 65_504.0F,
                            random.nextFloat() * 65_504.0F,
                            random.nextFloat() * 65_504.0F,
                            0.0F);
                } else if (kind == 2) {
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            random.nextFloat() * 2.0F - 1.0F,
                            random.nextFloat() * 2.0F - 1.0F,
                            random.nextFloat() * 2.0F - 1.0F,
                            random.nextFloat() * 3.0F - 1.0F);
                    putFloat(input, index, words, 2, 0, random.nextFloat() * 3.0F - 1.0F);
                } else if (kind == 3) {
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            random.nextFloat() * 65_504.0F,
                            random.nextFloat() * 10_000.0F,
                            random.nextFloat(),
                            0.0F);
                    putVec4(
                            input,
                            index,
                            words,
                            2,
                            random.nextFloat() * 65_504.0F,
                            random.nextFloat() * 65_504.0F,
                            random.nextFloat() * 65_504.0F,
                            random.nextFloat() * 65_504.0F);
                } else if (kind == 4) {
                    float[] guides = {0.0F, Float.MIN_VALUE, 1.0e-20F, 1.0e-6F, 0.1F, 1.0F};
                    putVec4(
                            input,
                            index,
                            words,
                            1,
                            random.nextFloat() * 65_504.0F,
                            random.nextFloat() * 65_504.0F,
                            random.nextFloat() * 65_504.0F,
                            0.0F);
                    putVec4(
                            input,
                            index,
                            words,
                            2,
                            guides[local % guides.length],
                            guides[(local / guides.length) % guides.length],
                            guides[(local / (guides.length * guides.length)) % guides.length],
                            0.0F);
                } else if (kind == 7) {
                    int optionalDirectionCase = local & 3;
                    putInt(input, index, words, 0, 1, optionalDirectionCase);
                    if (optionalDirectionCase == 0) {
                        putVec4(input, index, words, 1, 0.0F, 0.0F, 0.0F, 0.0F);
                    } else if (optionalDirectionCase == 1) {
                        putVec4(input, index, words, 1, 1.0F, 0.0F, 0.0F, 0.0F);
                    } else if (optionalDirectionCase == 2) {
                        putVec4(input, index, words, 1, 0.5F, 0.0F, 0.0F, 0.0F);
                    } else {
                        putVec4(
                                input,
                                index,
                                words,
                                1,
                                Float.intBitsToFloat(0x7fc0_0001),
                                0.0F,
                                0.0F,
                                0.0F);
                    }
                } else if (kind == 8) {
                    int directionCase = local & 3;
                    putInt(input, index, words, 0, 1, directionCase);
                    if (directionCase == 0) {
                        float x;
                        float y;
                        float z;
                        float lengthSquared;
                        do {
                            x = random.nextFloat() * 2.0F - 1.0F;
                            y = random.nextFloat() * 2.0F - 1.0F;
                            z = random.nextFloat() * 2.0F - 1.0F;
                            lengthSquared = x * x + y * y + z * z;
                        } while (lengthSquared < 1.0e-12F);
                        float inverseLength = 1.0F / (float) Math.sqrt(lengthSquared);
                        putVec4(
                                input,
                                index,
                                words,
                                1,
                                x * inverseLength,
                                y * inverseLength,
                                z * inverseLength,
                                0.0F);
                    } else if (directionCase == 1) {
                        putVec4(input, index, words, 1, 0.0F, 0.0F, 0.0F, 0.0F);
                    } else if (directionCase == 2) {
                        putVec4(input, index, words, 1, 0.5F, 0.0F, 0.0F, 0.0F);
                    } else {
                        putVec4(
                                input,
                                index,
                                words,
                                1,
                                Float.intBitsToFloat(0x7fc0_0001),
                                0.0F,
                                0.0F,
                                0.0F);
                    }
                } else {
                    for (int word = 1; word < words; word++) {
                        for (int component = 0; component < 4; component++) {
                            int bits = local < SPECIAL_FLOAT_BITS.length
                                    ? SPECIAL_FLOAT_BITS[(local + word + component)
                                            % SPECIAL_FLOAT_BITS.length]
                                    : Float.floatToRawIntBits(
                                            random.nextFloat() * 200_000.0F - 50_000.0F);
                            putInt(input, index, words, word, component, bits);
                        }
                    }
                }
            }
        }
        return input;
    }

    private static ByteBuffer fsrGuideCases(int kinds, int words) {
        ByteBuffer input = ShaderTestBuffer.inputs(CASES_PER_KIND * kinds, words);
        SplittableRandom random = new SplittableRandom(FSR_SEED);
        for (int kind = 0; kind < kinds; kind++) {
            for (int local = 0; local < CASES_PER_KIND; local++) {
                int index = kind * CASES_PER_KIND + local;
                putInt(input, index, words, 0, 0, kind + 1);
                if (kind == 0) {
                    int bits = local < SPECIAL_FLOAT_BITS.length
                            ? SPECIAL_FLOAT_BITS[local]
                            : Float.floatToRawIntBits(
                                    (local & 1) == 0
                                            ? positiveFloat(random, -30, 30)
                                            : -positiveFloat(random, -30, 30));
                    putInt(input, index, words, 1, 0, bits);
                } else if (kind == 1) {
                    for (int component = 0; component < 4; component++) {
                        int bits = local < SPECIAL_FLOAT_BITS.length
                                ? SPECIAL_FLOAT_BITS[
                                        (local + component) % SPECIAL_FLOAT_BITS.length]
                                : Float.floatToRawIntBits(
                                        random.nextFloat() * 8.0F - 4.0F);
                        putInt(input, index, words, 1, component, bits);
                    }
                } else if (kind == 2) {
                    for (int component = 0; component < 3; component++) {
                        int positionBits = local < SPECIAL_FLOAT_BITS.length
                                ? SPECIAL_FLOAT_BITS[
                                        (local + component)
                                                % SPECIAL_FLOAT_BITS.length]
                                : Float.floatToRawIntBits(
                                        random.nextFloat() * 200_000.0F
                                                - 50_000.0F);
                        putInt(
                                input,
                                index,
                                words,
                                1,
                                component,
                                positionBits);
                    }
                    float[] forward = randomUnitVector(random);
                    putVec4(
                            input,
                            index,
                            words,
                            2,
                            forward[0],
                            forward[1],
                            forward[2],
                            0.0F);
                } else {
                    int bits = local < SPECIAL_FLOAT_BITS.length
                            ? SPECIAL_FLOAT_BITS[local]
                            : Float.floatToRawIntBits(
                                    random.nextFloat() * 4.0F - 1.5F);
                    putInt(input, index, words, 1, 0, bits);
                }
            }
        }
        return input;
    }

    private static ByteBuffer queuedPsrCases(int words) {
        ByteBuffer input = ShaderTestBuffer.inputs(CASES_PER_KIND, words);
        SplittableRandom random = new SplittableRandom(QUEUED_PSR_SEED);
        for (int index = 0; index < CASES_PER_KIND; index++) {
            boolean forceOverflow = index == 1 || (index > 1 && random.nextInt(64) == 0);
            int count = forceOverflow ? 8 : index == 0 ? 0 : random.nextInt(9);
            int reflectionMask = random.nextInt(1 << count);
            putInt(input, index, words, 0, 0, count);
            putInt(input, index, words, 0, 1, reflectionMask);
            putInt(input, index, words, 0, 2, forceOverflow ? 1 : 0);

            float cameraX = random.nextFloat() * 64.0F - 32.0F;
            float cameraY = random.nextFloat() * 64.0F - 32.0F;
            float cameraZ = random.nextFloat() * 64.0F - 32.0F;
            putVec4(input, index, words, 1, cameraX, cameraY, cameraZ, 0.0F);
            putVec4(
                    input,
                    index,
                    words,
                    2,
                    cameraX + random.nextFloat() * 32.0F - 16.0F,
                    cameraY + random.nextFloat() * 32.0F - 16.0F,
                    cameraZ + random.nextFloat() * 32.0F - 16.0F,
                    0.0F);
            putRandomUnitVec4(input, index, words, 3, random);
            putRandomUnitVec4(input, index, words, 4, random);

            float positionX = cameraX;
            float positionY = cameraY;
            float positionZ = cameraZ;
            for (int delta = 0; delta < 8; delta++) {
                float[] direction = randomUnitVector(random);
                float distance = 0.125F + random.nextFloat() * 7.875F;
                positionX += direction[0] * distance;
                positionY += direction[1] * distance;
                positionZ += direction[2] * distance;
                putVec4(
                        input,
                        index,
                        words,
                        5 + delta,
                        positionX,
                        positionY,
                        positionZ,
                        0.0F);
                putRandomUnitVec4(input, index, words, 13 + delta, random);
            }
        }
        return input;
    }

    @Test
    void samplingIsDeterministicAndProducesUnitIntervalValues() throws IOException {
        int cases = 1 << 15;
        int inputWords = 2;
        int outputWords = 3;
        ByteBuffer input = ShaderTestBuffer.inputs(cases, inputWords);
        ShaderTestBuffer.setOutputWords(input, outputWords);
        SplittableRandom random = new SplittableRandom(SAMPLING_SEED);
        for (int index = 0; index < cases; index++) {
            for (int component = 0; component < 4; component++) {
                putInt(input, index, inputWords, 0, component, random.nextInt());
            }
            putInt(input, index, inputWords, 1, 0, random.nextInt());
            putInt(input, index, inputWords, 1, 1, random.nextInt());
            putInt(input, index, inputWords, 1, 2, random.nextInt(6));
            putInt(input, index, inputWords, 1, 3, random.nextInt(4));
        }
        int outputBytes = Math.multiplyExact(
                Math.multiplyExact(cases, outputWords),
                ShaderTestBuffer.WORD_BYTES);
        Path shader = slangShader("prime_sampling_parity.comp.spv");
        ByteBuffer first = runner.dispatch(
                shader,
                input,
                outputBytes,
                cases);
        ByteBuffer second = runner.dispatch(
                slangShader("prime_sampling_parity.comp.spv"),
                input,
                outputBytes,
                cases);
        for (int index = 0; index < cases; index++) {
            for (int component = 0; component < 4; component++) {
                int expected = ShaderTestBuffer.getInt(
                        first, index, outputWords, 0, component);
                int actual = ShaderTestBuffer.getInt(
                        second, index, outputWords, 0, component);
                if (actual != expected) {
                    throw new AssertionError(
                            "Sampling hash is not deterministic at case=" + index
                                    + " component=" + component
                                    + " first=0x" + Integer.toHexString(expected)
                                    + " second=0x" + Integer.toHexString(actual));
                }
            }
            for (int word = 1; word < outputWords; word++) {
                for (int component = 0; component < 4; component++) {
                    float expected = ShaderTestBuffer.getFloat(
                            first, index, outputWords, word, component);
                    float actual = ShaderTestBuffer.getFloat(
                            second, index, outputWords, word, component);
                    if (!Float.isFinite(actual)
                            || actual < 0.0F
                            || actual >= 1.0F
                            || Math.abs(actual - expected) > 1.0e-7F) {
                        throw new AssertionError(
                                "Sampling value is invalid or nondeterministic at case=" + index
                                        + " word=" + word
                                        + " component=" + component
                                        + " first=" + expected
                                        + " second=" + actual);
                    }
                }
            }
        }
    }

    private static Path slangShader(String name) {
        return Path.of(System.getProperty("prime.test.slangShaderDirectory"), name);
    }

    private static float positiveFloat(SplittableRandom random, int minimumExponent, int maximumExponent) {
        return Math.scalb(0.5F + random.nextFloat() * 0.5F,
                random.nextInt(minimumExponent, maximumExponent + 1));
    }

    private static float powerOfTwo(int exponent) {
        return Math.scalb(1.0F, exponent);
    }

    private static int pack(int x, int y, int z, int w) {
        return (x & 0xff)
                | ((y & 0xff) << 8)
                | ((z & 0xff) << 16)
                | ((w & 0xff) << 24);
    }

    private static float[] randomUnitVector(SplittableRandom random) {
        float z = random.nextFloat() * 2.0F - 1.0F;
        float angle = random.nextFloat() * (float) (Math.PI * 2.0);
        float radius = (float) Math.sqrt(Math.max(0.0F, 1.0F - z * z));
        return new float[] {
            radius * (float) Math.cos(angle),
            radius * (float) Math.sin(angle),
            z
        };
    }

    private static void putRandomUnitVec4(
            ByteBuffer input,
            int caseIndex,
            int words,
            int word,
            SplittableRandom random) {
        float[] direction = randomUnitVector(random);
        putVec4(
                input,
                caseIndex,
                words,
                word,
                direction[0],
                direction[1],
                direction[2],
                0.0F);
    }

    private static void putVec4(
            ByteBuffer input,
            int caseIndex,
            int words,
            int word,
            float x,
            float y,
            float z,
            float w) {
        putFloat(input, caseIndex, words, word, 0, x);
        putFloat(input, caseIndex, words, word, 1, y);
        putFloat(input, caseIndex, words, word, 2, z);
        putFloat(input, caseIndex, words, word, 3, w);
    }

    private static void putFloat(
            ByteBuffer input,
            int caseIndex,
            int words,
            int word,
            int component,
            float value) {
        ShaderTestBuffer.putFloat(input, caseIndex, words, word, component, value);
    }

    private static void putInt(
            ByteBuffer input,
            int caseIndex,
            int words,
            int word,
            int component,
            int value) {
        ShaderTestBuffer.putInt(input, caseIndex, words, word, component, value);
    }
}
