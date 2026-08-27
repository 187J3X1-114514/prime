package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gpu-shader")
final class ZSobolSamplerGpuTest {
    private static final int CASE_COUNT = 4_096;
    private static final int INPUT_WORDS = 2;
    private static final int OUTPUT_WORDS = 2;
    private static final long RANDOM_SEED = 0x5a53_4f42_4f4c_0001L;
    private static final int[] DIMENSION_ONE = {
        0x8000_0000, 0xc000_0000, 0xa000_0000, 0xf000_0000,
        0x8800_0000, 0xcc00_0000, 0xaa00_0000, 0xff00_0000,
        0x8080_0000, 0xc0c0_0000, 0xa0a0_0000, 0xf0f0_0000,
        0x8888_0000, 0xcccc_0000, 0xaaaa_0000, 0xffff_0000,
        0x8000_8000, 0xc000_c000, 0xa000_a000, 0xf000_f000,
        0x8800_8800, 0xcc00_cc00, 0xaa00_aa00, 0xff00_ff00,
        0x8080_8080, 0xc0c0_c0c0, 0xa0a0_a0a0, 0xf0f0_f0f0,
        0x8888_8888, 0xcccc_cccc, 0xaaaa_aaaa, 0xffff_ffff,
        0x8000_0000, 0xc000_0000, 0xa000_0000, 0xf000_0000,
        0x8800_0000, 0xcc00_0000, 0xaa00_0000, 0xff00_0000,
        0x8080_0000, 0xc0c0_0000, 0xa0a0_0000, 0xf0f0_0000,
        0x8888_0000, 0xcccc_0000, 0xaaaa_0000, 0xffff_0000,
        0x8000_8000, 0xc000_c000, 0xa000_a000, 0xf000_f000
    };
    private static final int[] PERMUTATIONS = {
        0, 1, 2, 3,  0, 1, 3, 2,  0, 2, 1, 3,  0, 2, 3, 1,
        0, 3, 2, 1,  0, 3, 1, 2,  1, 0, 2, 3,  1, 0, 3, 2,
        1, 2, 0, 3,  1, 2, 3, 0,  1, 3, 2, 0,  1, 3, 0, 2,
        2, 1, 0, 3,  2, 1, 3, 0,  2, 0, 1, 3,  2, 0, 3, 1,
        2, 3, 0, 1,  2, 3, 1, 0,  3, 1, 2, 0,  3, 1, 0, 2,
        3, 2, 1, 0,  3, 2, 0, 1,  3, 0, 2, 1,  3, 0, 1, 2
    };

    @Test
    void shaderMatchesPinnedPbrtZOrderFastOwenReference() throws IOException {
        ShaderComputeRunner opened;
        try {
            opened = ShaderComputeRunner.open();
        } catch (ShaderComputeRunner.UnavailableException | LinkageError exception) {
            if (Boolean.getBoolean("prime.shaderTests.required")) {
                throw new AssertionError(
                        "A Vulkan compute device is required for shader tests", exception);
            }
            Assumptions.assumeTrue(
                    false, "Vulkan shader tests unavailable: " + exception.getMessage());
            return;
        }
        try (ShaderComputeRunner runner = opened) {
            ByteBuffer input = cases();
            ByteBuffer output = runner.dispatch(
                    shader("prime_zsobol_parity.comp.spv"),
                    input,
                    CASE_COUNT * OUTPUT_WORDS * ShaderTestBuffer.WORD_BYTES,
                    CASE_COUNT);
            for (int caseIndex = 0; caseIndex < CASE_COUNT; caseIndex++) {
                int pixelX = input(caseIndex, 0, 0, input);
                int pixelY = input(caseIndex, 0, 1, input);
                int sampleIndex = input(caseIndex, 0, 2, input);
                int sampleEpoch = input(caseIndex, 0, 3, input);
                int extentX = input(caseIndex, 1, 0, input);
                int extentY = input(caseIndex, 1, 1, input);
                int vertexIndex = input(caseIndex, 1, 2, input);
                int pathBranch = input(caseIndex, 1, 3, input);
                float[] expected = sample(
                        pixelX,
                        pixelY,
                        extentX,
                        extentY,
                        sampleIndex,
                        sampleEpoch,
                        vertexIndex,
                        pathBranch);
                for (int component = 0; component < expected.length; component++) {
                    int word = component / 4;
                    int lane = component % 4;
                    assertEquals(
                            Float.floatToRawIntBits(expected[component]),
                            ShaderTestBuffer.getInt(
                                    output, caseIndex, OUTPUT_WORDS, word, lane),
                            "case=" + caseIndex + " component=" + component);
                }
            }
        }
    }

    @Test
    void everyPixelRetainsDyadicTemporalStratification() {
        int[][] cases = {
            {0, 0, 1, 1},
            {17, 29, 1920, 1080},
            {1919, 1079, 1920, 1080},
            {2731, 1535, 2732, 1536}
        };
        for (int[] sampleCase : cases) {
            for (int stream = 0; stream < 3; stream++) {
                for (int component = 0; component < 2; component++) {
                    boolean[] occupied = new boolean[256];
                    for (int sampleIndex = 0; sampleIndex < occupied.length; sampleIndex++) {
                        int value = sampleBits(
                                sampleCase[0],
                                sampleCase[1],
                                sampleCase[2],
                                sampleCase[3],
                                sampleIndex,
                                11,
                                1,
                                0)[stream * 2 + component];
                        int bin = value >>> 24;
                        assertFalse(
                                occupied[bin],
                                "pixel=" + sampleCase[0] + "," + sampleCase[1]
                                        + " stream=" + stream
                                        + " component=" + component
                                        + " bin=" + bin);
                        occupied[bin] = true;
                    }
                }
            }
        }
    }

    private static ByteBuffer cases() {
        int[][] extents = {
            {1, 1},
            {1280, 720},
            {1920, 1080},
            {2732, 1536},
            {3840, 2160},
            {8192, 4320}
        };
        ByteBuffer input = ShaderTestBuffer.inputs(CASE_COUNT, INPUT_WORDS);
        ShaderTestBuffer.setOutputWords(input, OUTPUT_WORDS);
        SplittableRandom random = new SplittableRandom(RANDOM_SEED);
        for (int caseIndex = 0; caseIndex < CASE_COUNT; caseIndex++) {
            int[] extent = extents[caseIndex % extents.length];
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 0, random.nextInt(extent[0]));
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 1, random.nextInt(extent[1]));
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 2, random.nextInt(1 << 16));
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 0, 3, random.nextInt(1 << 30));
            ShaderTestBuffer.putInt(input, caseIndex, INPUT_WORDS, 1, 0, extent[0]);
            ShaderTestBuffer.putInt(input, caseIndex, INPUT_WORDS, 1, 1, extent[1]);
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 1, 2, random.nextInt(1, 129));
            ShaderTestBuffer.putInt(
                    input, caseIndex, INPUT_WORDS, 1, 3, random.nextInt(2));
        }
        return input;
    }

    private static int input(
            int caseIndex, int word, int component, ByteBuffer input) {
        return ShaderTestBuffer.getInputInt(
                input, caseIndex, INPUT_WORDS, word, component);
    }

    private static float[] sample(
            int pixelX,
            int pixelY,
            int extentX,
            int extentY,
            int sampleIndex,
            int sampleEpoch,
            int vertexIndex,
            int pathBranch) {
        int[] bits = sampleBits(
                pixelX,
                pixelY,
                extentX,
                extentY,
                sampleIndex,
                sampleEpoch,
                vertexIndex,
                pathBranch);
        float[] result = new float[bits.length];
        for (int index = 0; index < result.length; index++) {
            result[index] = sampleFloat(bits[index]);
        }
        return result;
    }

    private static int[] sampleBits(
            int pixelX,
            int pixelY,
            int extentX,
            int extentY,
            int sampleIndex,
            int sampleEpoch,
            int vertexIndex,
            int pathBranch) {
        long mortonIndex = (morton(pixelX, pixelY) << 16)
                | Integer.toUnsignedLong(sampleIndex);
        int dimensionBase = (vertexIndex * 2 + pathBranch) * 6;
        int digitCount = 32 - Integer.numberOfLeadingZeros(
                Math.max(extentX, extentY) - 1) + 8;
        int[] result = new int[6];
        for (int stream = 0; stream < 3; stream++) {
            int dimension = dimensionBase + stream * 2;
            long mappedIndex = mappedIndex(mortonIndex, digitCount, dimension);
            long hash = sampleHash(dimension + 2, sampleEpoch);
            result[stream * 2] = fastOwen(
                    Integer.reverse((int) mappedIndex), (int) hash);
            result[stream * 2 + 1] = fastOwen(
                    dimensionOne(mappedIndex), (int) (hash >>> 32));
        }
        return result;
    }

    private static long mappedIndex(long mortonIndex, int digitCount, int dimension) {
        long sampleIndex = 0;
        for (int digitIndex = digitCount - 1; digitIndex >= 0; digitIndex--) {
            int digitShift = 2 * digitIndex;
            int digit = (int) ((mortonIndex >>> digitShift) & 3);
            long higherDigits = mortonIndex >>> (digitShift + 2);
            long dimensionHash = Integer.toUnsignedLong(0x5555_5555 * dimension);
            int permutation = (int) ((mixBits(higherDigits ^ dimensionHash) >>> 24) % 24);
            digit = PERMUTATIONS[permutation * 4 + digit];
            sampleIndex |= (long) digit << digitShift;
        }
        return sampleIndex;
    }

    private static long morton(int pixelX, int pixelY) {
        return (leftShift2(pixelY) << 1) | leftShift2(pixelX);
    }

    private static long leftShift2(int value) {
        long expanded = Integer.toUnsignedLong(value);
        expanded = (expanded ^ expanded << 16) & 0x0000_ffff_0000_ffffL;
        expanded = (expanded ^ expanded << 8) & 0x00ff_00ff_00ff_00ffL;
        expanded = (expanded ^ expanded << 4) & 0x0f0f_0f0f_0f0f_0f0fL;
        expanded = (expanded ^ expanded << 2) & 0x3333_3333_3333_3333L;
        return (expanded ^ expanded << 1) & 0x5555_5555_5555_5555L;
    }

    private static long mixBits(long value) {
        value ^= value >>> 31;
        value *= 0x7fb5_d329_728e_a185L;
        value ^= value >>> 27;
        value *= 0x81da_def4_bc2d_d44dL;
        return value ^ value >>> 33;
    }

    private static long sampleHash(int dimension, int seed) {
        long multiplier = 0xc6a4_a793_5bd1_e995L;
        long hash = 8 * multiplier;
        long key = Integer.toUnsignedLong(dimension)
                | Integer.toUnsignedLong(seed) << 32;
        key *= multiplier;
        key ^= key >>> 47;
        key *= multiplier;
        hash ^= key;
        hash *= multiplier;
        hash ^= hash >>> 47;
        hash *= multiplier;
        return hash ^ hash >>> 47;
    }

    private static int dimensionOne(long sampleIndex) {
        int value = 0;
        for (int column = 0; sampleIndex != 0; column++, sampleIndex >>>= 1) {
            if ((sampleIndex & 1) != 0) {
                value ^= DIMENSION_ONE[column];
            }
        }
        return value;
    }

    private static int fastOwen(int value, int seed) {
        value = Integer.reverse(value);
        value ^= value * 0x3d20_adea;
        value += seed;
        value *= (seed >>> 16) | 1;
        value ^= value * 0x0552_6c56;
        value ^= value * 0x53a2_2864;
        return Integer.reverse(value);
    }

    private static float sampleFloat(int value) {
        float scaled = (float) Integer.toUnsignedLong(value) * 0x1p-32F;
        return Math.min(scaled, Math.nextDown(1.0F));
    }

    private static Path shader(String name) {
        return Path.of(
                System.getProperty("prime.test.slangShaderDirectory"), name);
    }
}
