package dev.prime.render;

import java.util.Objects;
import java.util.OptionalInt;

/** CPU oracle for Prime's production auto-exposure histogram and EV adaptation contract. */
final class AutoExposureMath {
    static final int BIN_COUNT = 256;
    static final float MIN_LOG_LUMINANCE = -16.0F;
    static final float MAX_LOG_LUMINANCE = 20.0F;
    static final float KEY_LUMINANCE = 0.16F;
    static final float BASELINE_EV = -0.25F;
    static final float MIN_EV = -4.0F;
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
    private static final float LOG_LUMINANCE_RANGE =
            MAX_LOG_LUMINANCE - MIN_LOG_LUMINANCE;
    private static final float LN_10 = (float) Math.log(10.0);

    private AutoExposureMath() {
    }

    static OptionalInt histogramBin(float red, float green, float blue) {
        if (!Float.isFinite(red)
                || !Float.isFinite(green)
                || !Float.isFinite(blue)) {
            return OptionalInt.empty();
        }
        return histogramBinForLuminance(luminance(red, green, blue));
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
        float luminance = luminance(red, green, blue)
                * albedoScale(
                        albedoRed,
                        albedoGreen,
                        albedoBlue,
                        confidence);
        return histogramBinForLuminance(luminance);
    }

    static float albedoScale(
            float red, float green, float blue, float confidence) {
        float albedoLuminance = luminance(
                Math.clamp(red, 0.0F, 1.0F),
                Math.clamp(green, 0.0F, 1.0F),
                Math.clamp(blue, 0.0F, 1.0F));
        float fullScale =
                REFERENCE_ALBEDO / Math.max(albedoLuminance, MIN_ALBEDO);
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

    private static float luminance(float red, float green, float blue) {
        return 0.2627F * Math.max(red, 0.0F)
                + 0.6780F * Math.max(green, 0.0F)
                + 0.0593F * Math.max(blue, 0.0F);
    }

    private static OptionalInt histogramBinForLuminance(float luminance) {
        if (!Float.isFinite(luminance)) {
            return OptionalInt.empty();
        }
        float minimumLuminance = Math.scalb(1.0F, (int) MIN_LOG_LUMINANCE);
        float logLuminance = (float) (
                Math.log(Math.max(luminance, minimumLuminance))
                        / Math.log(2.0));
        float clamped = Math.clamp(
                logLuminance, MIN_LOG_LUMINANCE, MAX_LOG_LUMINANCE);
        int bin = (int) Math.floor(
                (clamped - MIN_LOG_LUMINANCE)
                        * (BIN_COUNT / LOG_LUMINANCE_RANGE));
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
        double weightedLogLuminance = 0.0;
        float minimumLogLuminance = 0.0F;
        float maximumLogLuminance = 0.0F;
        for (int bin = 0; bin < histogram.length; bin++) {
            long binBegin = cursor;
            long binEnd = cursor + histogram[bin];
            long retainedBegin = Math.max(binBegin, keepBegin);
            long retainedEnd = Math.min(binEnd, keepEnd);
            if (retainedEnd > retainedBegin) {
                long retained = retainedEnd - retainedBegin;
                float binLogLuminance = MIN_LOG_LUMINANCE
                        + (bin + 0.5F) * (LOG_LUMINANCE_RANGE / BIN_COUNT);
                if (retainedCount == 0L) {
                    minimumLogLuminance = binLogLuminance;
                }
                maximumLogLuminance = binLogLuminance;
                weightedLogLuminance += binLogLuminance * retained;
                retainedCount += retained;
            }
            cursor = binEnd;
        }
        if (retainedCount == 0L) {
            return previous;
        }

        float measuredLogLuminance =
                (float) (weightedLogLuminance / retainedCount);
        float targetEv = targetEv(
                measuredLogLuminance,
                minimumLogLuminance,
                maximumLogLuminance);
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
                exposureEv, true, targetEv, measuredLogLuminance);
    }

    static float sceneKeyBiasEv(
            float measuredLogLuminance,
            float minimumLogLuminance,
            float maximumLogLuminance) {
        float range = maximumLogLuminance - minimumLogLuminance;
        if (range <= 0.0F) {
            return 0.0F;
        }
        float q = (
                2.0F * measuredLogLuminance
                        - minimumLogLuminance
                        - maximumLogLuminance)
                / Math.max(range, SCENE_KEY_MIN_RANGE_EV);
        return 2.0F * q;
    }

    private static float targetEv(
            float measuredLogLuminance,
            float minimumLogLuminance,
            float maximumLogLuminance) {
        return Math.clamp(
                log2(KEY_LUMINANCE)
                        + BASELINE_EV
                        - measuredLogLuminance
                        + sceneKeyBiasEv(
                                measuredLogLuminance,
                                minimumLogLuminance,
                                maximumLogLuminance),
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
            float measuredLogLuminance) {
        State {
            if (!Float.isFinite(exposureEv)
                    || !Float.isFinite(targetEv)
                    || !Float.isFinite(measuredLogLuminance)) {
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
