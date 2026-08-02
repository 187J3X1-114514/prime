package dev.prime.render;

import java.util.Objects;
import java.util.OptionalInt;

/** CPU oracle for Prime's production auto-exposure histogram and EV adaptation contract. */
final class AutoExposureMath {
    static final int BIN_COUNT = 256;
    static final float MIN_LOG_BRIGHTNESS = -16.0F;
    static final float MAX_LOG_BRIGHTNESS = 20.0F;
    static final float KEY_BRIGHTNESS = 0.16F;
    static final float BASELINE_EV = 0.0F;
    static final float MIN_EV = 0.0F;
    static final float MAX_EV = 4.0F;
    static final float DARKEN_T90_SECONDS = 0.5F;
    static final float BRIGHTEN_T90_SECONDS = 2.0F;
    static final int TAIL_DENOMINATOR = 200;
    static final float REFERENCE_ALBEDO = 0.18F;
    static final float MIN_ALBEDO = 0.02F;
    static final float ALBEDO_BLEND = 1.0F;
    static final float SCENE_KEY_MIN_RANGE_EV = 2.0F;
    static final int MATERIAL_DIELECTRIC = 0;
    static final int MATERIAL_FOLIAGE = 3;
    private static final float LOG_BRIGHTNESS_RANGE =
            MAX_LOG_BRIGHTNESS - MIN_LOG_BRIGHTNESS;
    private static final float LN_10 = (float) Math.log(10.0);

    private AutoExposureMath() {
    }

    static OptionalInt histogramBin(float red, float green, float blue) {
        if (!Float.isFinite(red)
                || !Float.isFinite(green)
                || !Float.isFinite(blue)) {
            return OptionalInt.empty();
        }
        return histogramBinForBrightness(meteringBrightness(red, green, blue));
    }

    static OptionalInt histogramBin(
            float red,
            float green,
            float blue,
            float albedoRed,
            float albedoGreen,
            float albedoBlue,
            float confidence) {
        if (!Float.isFinite(red)
                || !Float.isFinite(green)
                || !Float.isFinite(blue)
                || !Float.isFinite(albedoRed)
                || !Float.isFinite(albedoGreen)
                || !Float.isFinite(albedoBlue)
                || !Float.isFinite(confidence)) {
            return OptionalInt.empty();
        }
        float brightness = meteringBrightness(red, green, blue)
                * albedoScale(
                        albedoRed,
                        albedoGreen,
                        albedoBlue,
                        confidence);
        return histogramBinForBrightness(brightness);
    }

    static float albedoScale(
            float red, float green, float blue, float confidence) {
        float albedoBrightness = meteringBrightness(
                Math.clamp(red, 0.0F, 1.0F),
                Math.clamp(green, 0.0F, 1.0F),
                Math.clamp(blue, 0.0F, 1.0F));
        float fullScale =
                REFERENCE_ALBEDO / Math.max(albedoBrightness, MIN_ALBEDO);
        float blendedScale = 1.0F + (fullScale - 1.0F) * ALBEDO_BLEND;
        return 1.0F + (blendedScale - 1.0F)
                * Math.clamp(confidence, 0.0F, 1.0F);
    }

    static float materialConfidence(
            int materialClass,
            float primaryDistance) {
        boolean diffuseSurface = materialClass == MATERIAL_DIELECTRIC
                || materialClass == MATERIAL_FOLIAGE;
        return diffuseSurface && primaryDistance >= 0.0F ? 1.0F : 0.0F;
    }

    static float meteringBrightness(float red, float green, float blue) {
        float nonNegativeRed = Math.max(red, 0.0F);
        float nonNegativeGreen = Math.max(green, 0.0F);
        float nonNegativeBlue = Math.max(blue, 0.0F);
        float bt709Red = Math.max(
                1.6604910F * nonNegativeRed
                        - 0.5876411F * nonNegativeGreen
                        - 0.0728499F * nonNegativeBlue,
                0.0F);
        float bt709Green = Math.max(
                -0.1245505F * nonNegativeRed
                        + 1.1328999F * nonNegativeGreen
                        - 0.0083494F * nonNegativeBlue,
                0.0F);
        float bt709Blue = Math.max(
                -0.0181508F * nonNegativeRed
                        - 0.1005789F * nonNegativeGreen
                        + 1.1187297F * nonNegativeBlue,
                0.0F);
        float l = 0.4122214708F * bt709Red
                + 0.5363325363F * bt709Green
                + 0.0514459929F * bt709Blue;
        float m = 0.2119034982F * bt709Red
                + 0.6806995451F * bt709Green
                + 0.1073969566F * bt709Blue;
        float s = 0.0883024619F * bt709Red
                + 0.2817188376F * bt709Green
                + 0.6299787005F * bt709Blue;
        float lightness = 0.2104542553F * (float) Math.cbrt(l)
                + 0.7936177850F * (float) Math.cbrt(m)
                - 0.0040720468F * (float) Math.cbrt(s);
        return lightness * lightness * lightness;
    }

    private static OptionalInt histogramBinForBrightness(float brightness) {
        if (!Float.isFinite(brightness)) {
            return OptionalInt.empty();
        }
        float minimumBrightness = Math.scalb(1.0F, (int) MIN_LOG_BRIGHTNESS);
        float logBrightness = (float) (
                Math.log(Math.max(brightness, minimumBrightness))
                        / Math.log(2.0));
        float clamped = Math.clamp(
                logBrightness, MIN_LOG_BRIGHTNESS, MAX_LOG_BRIGHTNESS);
        int bin = (int) Math.floor(
                (clamped - MIN_LOG_BRIGHTNESS)
                        * (BIN_COUNT / LOG_BRIGHTNESS_RANGE));
        return OptionalInt.of(Math.min(bin, BIN_COUNT - 1));
    }

    static State update(
            State previous,
            int[] histogram,
            float deltaSeconds,
            boolean reset,
            boolean instant) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(histogram, "histogram");
        if (histogram.length != BIN_COUNT) {
            throw new IllegalArgumentException(
                    "Auto-exposure histogram must have 256 bins");
        }
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0F) {
            throw new IllegalArgumentException(
                    "Auto-exposure frame delta must be finite and non-negative");
        }

        long validCount = 0L;
        for (int count : histogram) {
            if (count < 0) {
                throw new IllegalArgumentException(
                        "Auto-exposure histogram counts must be non-negative");
            }
            validCount = Math.addExact(validCount, count);
        }
        if (validCount == 0L) {
            return reset || instant || !previous.initialized
                    ? State.initializedAtZero()
                    : previous;
        }

        long tailCount = validCount / TAIL_DENOMINATOR;
        long keepBegin = tailCount;
        long keepEnd = validCount - tailCount;
        long cursor = 0L;
        long retainedCount = 0L;
        double weightedLogBrightness = 0.0;
        float minimumLogBrightness = 0.0F;
        float maximumLogBrightness = 0.0F;
        for (int bin = 0; bin < histogram.length; bin++) {
            long binBegin = cursor;
            long binEnd = cursor + histogram[bin];
            long retainedBegin = Math.max(binBegin, keepBegin);
            long retainedEnd = Math.min(binEnd, keepEnd);
            if (retainedEnd > retainedBegin) {
                long retained = retainedEnd - retainedBegin;
                float binLogBrightness = MIN_LOG_BRIGHTNESS
                        + (bin + 0.5F) * (LOG_BRIGHTNESS_RANGE / BIN_COUNT);
                if (retainedCount == 0L) {
                    minimumLogBrightness = binLogBrightness;
                }
                maximumLogBrightness = binLogBrightness;
                weightedLogBrightness += binLogBrightness * retained;
                retainedCount += retained;
            }
            cursor = binEnd;
        }
        if (retainedCount == 0L) {
            return previous;
        }

        float measuredLogBrightness =
                (float) (weightedLogBrightness / retainedCount);
        float targetEv = targetEv(
                measuredLogBrightness,
                minimumLogBrightness,
                maximumLogBrightness);
        float exposureEv = targetEv;
        if (!reset && !instant && previous.initialized) {
            float t90 = targetEv < previous.exposureEv
                    ? DARKEN_T90_SECONDS
                    : BRIGHTEN_T90_SECONDS;
            float blend = 1.0F - (float) Math.exp(
                    -deltaSeconds * LN_10 / t90);
            float clampedBlend = Math.clamp(blend, 0.0F, 1.0F);
            exposureEv = previous.exposureEv
                    + (targetEv - previous.exposureEv) * clampedBlend;
        }
        return new State(
                exposureEv, true, targetEv, measuredLogBrightness);
    }

    static float sceneKeyBiasEv(
            float measuredLogBrightness,
            float minimumLogBrightness,
            float maximumLogBrightness) {
        float range = maximumLogBrightness - minimumLogBrightness;
        if (range <= 0.0F) {
            return 0.0F;
        }
        float q = (
                2.0F * measuredLogBrightness
                        - minimumLogBrightness
                        - maximumLogBrightness)
                / Math.max(range, SCENE_KEY_MIN_RANGE_EV);
        return 2.0F * q;
    }

    private static float targetEv(
            float measuredLogBrightness,
            float minimumLogBrightness,
            float maximumLogBrightness) {
        return Math.clamp(
                log2(KEY_BRIGHTNESS)
                        + BASELINE_EV
                        - measuredLogBrightness
                        + sceneKeyBiasEv(
                                measuredLogBrightness,
                                minimumLogBrightness,
                                maximumLogBrightness),
                MIN_EV,
                MAX_EV);
    }

    private static float log2(float value) {
        return (float) (Math.log(value) / Math.log(2.0));
    }

    record State(
            float exposureEv,
            boolean initialized,
            float targetEv,
            float measuredLogBrightness) {
        State {
            if (!Float.isFinite(exposureEv)
                    || !Float.isFinite(targetEv)
                    || !Float.isFinite(measuredLogBrightness)) {
                throw new IllegalArgumentException(
                        "Auto-exposure state must be finite");
            }
        }

        static State initial() {
            return new State(0.0F, false, 0.0F, 0.0F);
        }

        static State initializedAtZero() {
            return new State(0.0F, true, 0.0F, 0.0F);
        }
    }
}
