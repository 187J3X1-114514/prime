package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class AutoExposureMathTest {
    private static final float BIN_HALF_WIDTH_EV =
            (AutoExposureMath.MAX_LOG_BRIGHTNESS
                    - AutoExposureMath.MIN_LOG_BRIGHTNESS)
                    / AutoExposureMath.BIN_COUNT
                    * 0.5F;

    @Test
    void histogramUsesLinearRec2020LuminanceAndRejectsNonFinitePixels() {
        OptionalInt red = AutoExposureMath.histogramBin(1.0F, 0.0F, 0.0F);
        OptionalInt green = AutoExposureMath.histogramBin(0.0F, 1.0F, 0.0F);
        OptionalInt blue = AutoExposureMath.histogramBin(0.0F, 0.0F, 1.0F);

        assertTrue(red.isPresent());
        assertTrue(green.isPresent());
        assertTrue(blue.isPresent());
        assertTrue(green.getAsInt() > red.getAsInt());
        assertTrue(red.getAsInt() > blue.getAsInt());
        assertEquals(
                1.0F,
                AutoExposureMath.meteringBrightness(1.0F, 1.0F, 1.0F),
                2.0e-6F);
        assertEquals(
                0.2627F,
                AutoExposureMath.meteringBrightness(1.0F, 0.0F, 0.0F),
                2.0e-6F);
        assertEquals(
                0.6780F,
                AutoExposureMath.meteringBrightness(0.0F, 1.0F, 0.0F),
                2.0e-6F);
        assertEquals(
                0.0593F,
                AutoExposureMath.meteringBrightness(0.0F, 0.0F, 1.0F),
                2.0e-6F);
        assertEquals(
                4.0F * AutoExposureMath.meteringBrightness(
                        0.2F, 0.5F, 0.8F),
                AutoExposureMath.meteringBrightness(0.8F, 2.0F, 3.2F),
                2.0e-6F);
        assertEquals(0, AutoExposureMath.histogramBin(0.0F, 0.0F, 0.0F)
                .orElseThrow());
        assertFalse(AutoExposureMath.histogramBin(
                Float.NaN, 0.0F, 0.0F).isPresent());
        assertFalse(AutoExposureMath.histogramBin(
                Float.POSITIVE_INFINITY, 0.0F, 0.0F).isPresent());
    }

    @Test
    void diffuseAlbedoCorrectionNeutralizesBrightness() {
        int reference = AutoExposureMath.histogramBin(
                0.18F,
                0.18F,
                0.18F,
                0.18F,
                0.18F,
                0.18F,
                1.0F).orElseThrow();
        int white = AutoExposureMath.histogramBin(
                0.72F,
                0.72F,
                0.72F,
                0.72F,
                0.72F,
                0.72F,
                1.0F).orElseThrow();
        int uncorrectedWhite = AutoExposureMath.histogramBin(
                0.72F, 0.72F, 0.72F).orElseThrow();
        int snow = AutoExposureMath.histogramBin(
                1.0F,
                1.0F,
                1.0F,
                1.0F,
                1.0F,
                1.0F,
                1.0F).orElseThrow();
        int dark = AutoExposureMath.histogramBin(
                0.09F,
                0.09F,
                0.09F,
                0.09F,
                0.09F,
                0.09F,
                1.0F).orElseThrow();

        assertEquals(reference, white);
        assertTrue(uncorrectedWhite > white);
        assertEquals(white, snow);
        assertTrue(snow < uncorrectedWhite);
        assertEquals(reference, dark);
        assertTrue(dark > AutoExposureMath.histogramBin(
                0.09F, 0.09F, 0.09F).orElseThrow());
        assertEquals(
                1.0F,
                AutoExposureMath.albedoScale(
                        1.0F, 1.0F, 1.0F, 0.0F));
        assertFalse(AutoExposureMath.histogramBin(
                1.0F,
                1.0F,
                1.0F,
                Float.NaN,
                1.0F,
                1.0F,
                1.0F).isPresent());
    }

    @Test
    void albedoCorrectionUsesFullProtectedNeutralizedBrightness() {
        assertEquals(
                1.0F + (AutoExposureMath.REFERENCE_ALBEDO - 1.0F)
                        * AutoExposureMath.ALBEDO_BLEND,
                AutoExposureMath.albedoScale(
                        1.0F, 1.0F, 1.0F, 1.0F),
                1.0e-6F);
        assertEquals(
                1.0F + (AutoExposureMath.REFERENCE_ALBEDO
                        / AutoExposureMath.MIN_ALBEDO - 1.0F)
                        * AutoExposureMath.ALBEDO_BLEND,
                AutoExposureMath.albedoScale(
                        0.0F, 0.0F, 0.0F, 1.0F),
                1.0e-6F);
        assertEquals(
                1.0F + (1.0F + (AutoExposureMath.REFERENCE_ALBEDO - 1.0F)
                        * AutoExposureMath.ALBEDO_BLEND - 1.0F) * 0.5F,
                AutoExposureMath.albedoScale(
                        1.0F, 1.0F, 1.0F, 0.5F),
                1.0e-6F);
        assertEquals(
                2.4739F,
                (float) (-Math.log(AutoExposureMath.albedoScale(
                        1.0F, 1.0F, 1.0F, 1.0F)) / Math.log(2.0)),
                1.0e-4F);
    }

    @Test
    void albedoCorrectionExcludesInvalidSpecularAndTransmissiveGuides() {
        assertEquals(
                1.0F,
                AutoExposureMath.materialConfidence(
                        AutoExposureMath.MATERIAL_DIELECTRIC, 1.0F));
        assertEquals(
                1.0F,
                AutoExposureMath.materialConfidence(
                        AutoExposureMath.MATERIAL_FOLIAGE, 1.0F));
        assertEquals(
                0.0F,
                AutoExposureMath.materialConfidence(1, 1.0F));
        assertEquals(
                0.0F,
                AutoExposureMath.materialConfidence(2, 1.0F));
        assertEquals(
                0.0F,
                AutoExposureMath.materialConfidence(
                        AutoExposureMath.MATERIAL_DIELECTRIC, -1.0F));
    }

    @Test
    void robustMeterDiscardsTheDarkestAndBrightestHalfPercent() {
        int[] histogram = new int[AutoExposureMath.BIN_COUNT];
        int keyBin = binForGray(AutoExposureMath.KEY_BRIGHTNESS);
        histogram[0] = 1;
        histogram[1] = 1;
        histogram[keyBin] = 197;
        histogram[AutoExposureMath.BIN_COUNT - 1] = 1;

        AutoExposureMath.State result = AutoExposureMath.update(
                AutoExposureMath.State.initial(),
                histogram,
                0.0F,
                true,
                false);

        float binWidth = (
                AutoExposureMath.MAX_LOG_BRIGHTNESS
                        - AutoExposureMath.MIN_LOG_BRIGHTNESS)
                / AutoExposureMath.BIN_COUNT;
        float innerDarkLog =
                AutoExposureMath.MIN_LOG_BRIGHTNESS + 1.5F * binWidth;
        float keyLog = AutoExposureMath.MIN_LOG_BRIGHTNESS
                + (keyBin + 0.5F) * binWidth;
        float expectedMeasuredLog =
                (innerDarkLog + 197.0F * keyLog) / 198.0F;
        assertEquals(
                expectedMeasuredLog,
                result.measuredLogBrightness(),
                1.0e-6F);
        assertEquals(result.targetEv(), result.exposureEv());
    }

    @Test
    void reinhardSceneKeyUsesTheOriginalFourToTheQStrength() {
        assertEquals(
                0.0F,
                AutoExposureMath.sceneKeyBiasEv(2.0F, 0.0F, 4.0F));
        assertEquals(
                1.0F,
                AutoExposureMath.sceneKeyBiasEv(3.0F, 0.0F, 4.0F));
        assertEquals(
                -1.0F,
                AutoExposureMath.sceneKeyBiasEv(1.0F, 0.0F, 4.0F));
        assertEquals(
                2.0F,
                AutoExposureMath.sceneKeyBiasEv(4.0F, 0.0F, 4.0F));
        assertEquals(
                -2.0F,
                AutoExposureMath.sceneKeyBiasEv(0.0F, 0.0F, 4.0F));
        assertEquals(
                0.0F,
                AutoExposureMath.sceneKeyBiasEv(2.0F, 2.0F, 2.0F));
    }

    @Test
    void reinhardSceneKeyAttenuatesNarrowHistogramNoise() {
        assertEquals(
                1.0F,
                AutoExposureMath.sceneKeyBiasEv(1.0F, 0.0F, 1.0F));
        assertEquals(
                -1.0F,
                AutoExposureMath.sceneKeyBiasEv(0.0F, 0.0F, 1.0F));
        assertEquals(
                0.140625F,
                AutoExposureMath.sceneKeyBiasEv(0.140625F, 0.0F, 0.140625F));
    }

    @Test
    void reinhardSceneKeyPreservesDominantBrightAndDarkCompositions() {
        int center = binForGray(AutoExposureMath.KEY_BRIGHTNESS);
        int low = center - 8;
        int high = center + 8;
        int[] highKeyHistogram = new int[AutoExposureMath.BIN_COUNT];
        highKeyHistogram[low] = 20;
        highKeyHistogram[high] = 80;
        int[] lowKeyHistogram = new int[AutoExposureMath.BIN_COUNT];
        lowKeyHistogram[low] = 80;
        lowKeyHistogram[high] = 20;

        AutoExposureMath.State averageKey =
                instantForGray(AutoExposureMath.KEY_BRIGHTNESS);
        AutoExposureMath.State highKey = AutoExposureMath.update(
                AutoExposureMath.State.initial(),
                highKeyHistogram,
                0.0F,
                true,
                true);
        AutoExposureMath.State lowKey = AutoExposureMath.update(
                AutoExposureMath.State.initial(),
                lowKeyHistogram,
                0.0F,
                true,
                true);

        assertTrue(highKey.targetEv() > averageKey.targetEv());
        assertTrue(lowKey.targetEv() < averageKey.targetEv());
    }

    @Test
    void baselineIsNeutralAndHalfStrengthExposureIsSymmetricallyBounded() {
        AutoExposureMath.State day = instantForGray(0.08F);
        AutoExposureMath.State moderatelyDark = instantForGray(
                Math.scalb(AutoExposureMath.KEY_BRIGHTNESS, -4));
        AutoExposureMath.State dark = instantForGray(
                Math.scalb(AutoExposureMath.KEY_BRIGHTNESS, -8));
        AutoExposureMath.State middle = instantForGray(
                AutoExposureMath.KEY_BRIGHTNESS);
        AutoExposureMath.State bright = instantForGray(
                Math.scalb(AutoExposureMath.KEY_BRIGHTNESS, 8));

        assertEquals(0.5F, day.targetEv(), BIN_HALF_WIDTH_EV);
        assertEquals(2.0F, moderatelyDark.targetEv(), BIN_HALF_WIDTH_EV);
        assertEquals(4.0F, dark.targetEv(), BIN_HALF_WIDTH_EV);
        assertEquals(
                AutoExposureMath.BASELINE_EV,
                middle.targetEv(),
                BIN_HALF_WIDTH_EV);
        assertEquals(-4.0F, bright.targetEv(), BIN_HALF_WIDTH_EV);
        assertTrue(dark.targetEv() > middle.targetEv());
        assertTrue(middle.targetEv() >= bright.targetEv());
    }

    @Test
    void invalidHistogramRetainsExposureUnlessAResetNeedsInitialization() {
        int[] empty = new int[AutoExposureMath.BIN_COUNT];
        AutoExposureMath.State previous =
                new AutoExposureMath.State(2.0F, true, 3.0F, -5.0F);

        assertSame(
                previous,
                AutoExposureMath.update(
                        previous, empty, 1.0F, false, false));
        assertEquals(
                AutoExposureMath.State.initializedAtZero(),
                AutoExposureMath.update(
                        AutoExposureMath.State.initial(),
                        empty,
                        1.0F,
                        true,
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoExposureMath.update(
                        previous, new int[1], 1.0F, false, false));
    }

    @Test
    void adaptationReachesNinetyPercentAtTheConfiguredTimes() {
        AutoExposureMath.State zero =
                AutoExposureMath.State.initializedAtZero();
        AutoExposureMath.State darkTarget = instantForGray(
                Math.scalb(AutoExposureMath.KEY_BRIGHTNESS, -4));
        AutoExposureMath.State brightening = AutoExposureMath.update(
                zero,
                histogramForGray(Math.scalb(
                        AutoExposureMath.KEY_BRIGHTNESS, -4)),
                AutoExposureMath.BRIGHTEN_T90_SECONDS,
                false,
                false);
        assertEquals(
                darkTarget.targetEv() * 0.9F,
                brightening.exposureEv(),
                2.0e-3F);

        AutoExposureMath.State fourEv =
                new AutoExposureMath.State(4.0F, true, 4.0F, -8.0F);
        AutoExposureMath.State brightTarget = instantForGray(
                AutoExposureMath.KEY_BRIGHTNESS);
        AutoExposureMath.State darkening = AutoExposureMath.update(
                fourEv,
                histogramForGray(AutoExposureMath.KEY_BRIGHTNESS),
                AutoExposureMath.DARKEN_T90_SECONDS,
                false,
                false);
        assertEquals(
                brightTarget.targetEv()
                        + (4.0F - brightTarget.targetEv()) * 0.1F,
                darkening.exposureEv(),
                2.0e-3F);
    }

    private static AutoExposureMath.State instantForGray(float brightness) {
        return AutoExposureMath.update(
                AutoExposureMath.State.initial(),
                histogramForGray(brightness),
                0.0F,
                true,
                true);
    }

    private static int[] histogramForGray(float brightness) {
        int[] histogram = new int[AutoExposureMath.BIN_COUNT];
        histogram[binForGray(brightness)] = 100;
        return histogram;
    }

    private static int binForGray(float brightness) {
        return AutoExposureMath.histogramBin(
                brightness, brightness, brightness).orElseThrow();
    }
}
