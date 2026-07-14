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

    static float sample(int pixelX, int pixelY, int sampleIndex, int epoch, int dimension) {
        int seed = hash(pixelX ^ hash(pixelY));
        seed ^= hash(sampleIndex + 0x9e37_79b9);
        seed ^= hash(epoch + 0x85eb_ca6b);
        seed ^= hash(dimension + 0xc2b2_ae35);
        return (hash(seed) >>> 8) * (1.0F / 16_777_216.0F);
    }

    static float diffusePdf(float cosine) {
        return Math.max(cosine, 0.0F) / (float) Math.PI;
    }

    static float environmentPdf(float cosine) {
        return cosine > 0.0F ? 1.0F / (2.0F * (float) Math.PI) : 0.0F;
    }

    private static int hash(int value) {
        value ^= value >>> 16;
        value *= 0x7feb_352d;
        value ^= value >>> 15;
        value *= 0x846c_a68b;
        return value ^ value >>> 16;
    }
}
