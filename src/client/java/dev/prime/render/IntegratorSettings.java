package dev.prime.render;

/**
 * Internal, deliberately small adapter between Minecraft's world and the path integrator.
 *
 * <p>All RGB radiance written here is linear Rec.2020 D65. That meaning is part of the shader ABI:
 * adapters may change the light model, but must not supply encoded sRGB or silently change the RGB
 * basis without migrating every material, path-state, accumulation, and presentation boundary.
 */
final class IntegratorSettings {
    static final int MAXIMUM_BOUNCES = 256;
    static final int RUSSIAN_ROULETTE_START = 2;
    static final int SAMPLE_EFFECT_CAMERA = 0;
    static final int SAMPLE_EFFECT_DIRECT_ENVIRONMENT = 1;
    static final int SAMPLE_EFFECT_DIRECT_SUN = 2;
    static final int SAMPLE_EFFECT_SCATTER_BSDF = 3;

    private static final int SOBOL_INDEX_MASK = 0xffff_0000;
    private static final float UINT32_TO_FLOAT_EXCLUSIVE_SCALE = 1.0F / 4_294_967_808.0F;
    private static final int[] SOBOL_DIMENSION_ONE = new int[] {
        0x00000001, 0x00000003, 0x00000005, 0x0000000f,
        0x00000011, 0x00000033, 0x00000055, 0x000000ff,
        0x00000101, 0x00000303, 0x00000505, 0x00000f0f,
        0x00001111, 0x00003333, 0x00005555, 0x0000ffff,
        0x00010001, 0x00030003, 0x00050005, 0x000f000f,
        0x00110011, 0x00330033, 0x00550055, 0x00ff00ff,
        0x01010101, 0x03030303, 0x05050505, 0x0f0f0f0f,
        0x11111111, 0x33333333, 0x55555555, 0xffffffff
    };

    private IntegratorSettings() {
    }

    static float powerHeuristic(float firstPdf, float secondPdf) {
        float first = firstPdf * firstPdf;
        float second = secondPdf * secondPdf;
        return first / Math.max(first + second, 1.0e-30F);
    }

    static float rouletteSurvival(float maximumThroughput) {
        return Math.max(0.05F, Math.min(0.95F, maximumThroughput));
    }

    static float updateMean(float previousMean, float sample, int sampleIndex) {
        if (sampleIndex < 0) {
            throw new IllegalArgumentException("Sample index must not be negative");
        }
        return sampleIndex == 0
                ? sample
                : previousMean + (sample - previousMean) / (sampleIndex + 1.0F);
    }

    /** CPU reference for the grouped two-dimensional shader sequence. */
    static float[] sobolSample2D(
            int pixelX,
            int pixelY,
            int sampleIndex,
            int sampleEpoch,
            int vertexIndex,
            int effect,
            int dimensionSet) {
        int seed = hash32(pixelX);
        seed = hashCombine(seed, pixelY);
        seed = hashCombine(seed, sampleEpoch);
        seed = hashCombine(seed, vertexIndex);
        int mixedSeed = hashCombine(seed, effect) ^ highQualityHash(dimensionSet);
        int shuffledIndex = reversedBitOwen(
                Integer.reverse(sampleIndex), mixedSeed ^ 0xf8ad_e99a) & SOBOL_INDEX_MASK;
        return new float[] {
            sobolBurley(shuffledIndex, 0, mixedSeed ^ 0xe0aa_af76),
            sobolBurley(shuffledIndex, 1, mixedSeed ^ 0x9496_4d4e)
        };
    }

    static float diffusePdf(float cosine) {
        return Math.max(cosine, 0.0F) / (float) Math.PI;
    }

    static float environmentPdf(float cosine) {
        return cosine > 0.0F ? 1.0F / (2.0F * (float) Math.PI) : 0.0F;
    }

    private static float sobolBurley(int reversedBitIndex, int dimension, int seed) {
        int result = 0;
        if (dimension == 0) {
            result = Integer.reverse(reversedBitIndex);
        } else {
            int index = reversedBitIndex;
            int tableIndex = 0;
            while (index != 0) {
                int leadingZeroes = Integer.numberOfLeadingZeros(index);
                result ^= SOBOL_DIMENSION_ONE[tableIndex + leadingZeroes];
                tableIndex += leadingZeroes + 1;
                index <<= leadingZeroes;
                index <<= 1;
            }
        }
        long unsigned = Integer.toUnsignedLong(
                Integer.reverse(reversedBitOwen(result, seed)));
        return (float) unsigned * UINT32_TO_FLOAT_EXCLUSIVE_SCALE;
    }

    private static int reversedBitOwen(int value, int seed) {
        value ^= value * 0x3d20_adea;
        value += seed;
        value *= (seed >>> 16) | 1;
        value ^= value * 0x0552_6c56;
        value ^= value * 0x53a2_2864;
        return value;
    }

    private static int highQualityHash(int value) {
        value ^= value >>> 16;
        value *= 0x21f0_aaad;
        value ^= value >>> 15;
        value *= 0xd35a_2d97;
        value ^= value >>> 15;
        return value ^ 0xe6fe_3beb;
    }

    private static int hash32(int value) {
        value ^= value >>> 16;
        value *= 0x21f0_aaad;
        value ^= value >>> 15;
        value *= 0xf35a_2d97;
        return value ^ value >>> 15;
    }

    private static int hashCombine(int seed, int value) {
        return seed ^ (hash32(value) + 0x9e37_79b9 + (seed << 6) + (seed >>> 2));
    }
}
