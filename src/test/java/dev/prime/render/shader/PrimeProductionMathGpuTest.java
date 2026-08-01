package dev.prime.render.shader;

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
        int kinds = 7;
        int inputWords = 3;
        ShaderPropertyBatch.assertProperties(
                runner,
                shader("prime_transport_properties.comp.spv"),
                transportCases(kinds, inputWords),
                CASES_PER_KIND * kinds,
                inputWords,
                7,
                TRANSPORT_SEED);
    }

    @Test
    void celestialFramePreservesPolesEquatorialCoordinatesAndDailyRotation()
            throws IOException {
        int kinds = 5;
        int inputWords = 2;
        ShaderPropertyBatch.assertProperties(
                runner,
                shader("prime_celestial_properties.comp.spv"),
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
                shader("prime_material_properties.comp.spv"),
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
                shader("prime_nrd_properties.comp.spv"),
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
                shader("prime_fsr_input_properties.comp.spv"),
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
                shader("prime_fsr_input_properties.comp.spv"),
                input,
                CASES_PER_KIND * kinds,
                inputWords,
                5,
                FSR_SEED);
    }

    @Test
    void autoExposureTargetAdaptationAndAlbedoMeteringUseTheProductionContract()
            throws IOException {
        int kinds = 6;
        int inputWords = 2;
        ByteBuffer input = ShaderTestBuffer.inputs(
                CASES_PER_KIND * kinds, inputWords);
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
                    putVec4(
                            input,
                            index,
                            inputWords,
                            1,
                            measured,
                            minimum,
                            maximum,
                            0.0F);
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
                } else {
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
                }
            }
        }
        ShaderPropertyBatch.assertProperties(
                runner,
                shader("prime_auto_exposure_properties.comp.spv"),
                input,
                CASES_PER_KIND * kinds,
                inputWords,
                4,
                AUTO_EXPOSURE_SEED);
    }

    @Test
    void queuedPsrMatchesTheExplicitDeltaChain() throws IOException {
        int inputWords = 21;
        ShaderPropertyBatch.assertProperties(
                runner,
                shader("prime_queued_psr_properties.comp.spv"),
                queuedPsrCases(inputWords),
                CASES_PER_KIND,
                inputWords,
                6,
                QUEUED_PSR_SEED);
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
                            random.nextFloat());
                } else if (kind == 6) {
                    float rayDistance = powerOfTwo(random.nextInt(-10, 13));
                    float[] hits = {
                        random.nextFloat() * rayDistance,
                        random.nextFloat() * rayDistance,
                        random.nextFloat() * rayDistance,
                        random.nextFloat() * rayDistance
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
                            -rayDistance,
                            2.0F * rayDistance,
                            0.0F);
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
                putInt(input, index, words, 2, 0, local & 0xff);
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

    private static Path shader(String name) {
        return Path.of(System.getProperty("prime.test.shaderDirectory"), name);
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
