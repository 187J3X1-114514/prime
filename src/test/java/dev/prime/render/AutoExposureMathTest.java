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
            (AutoExposureMath.MAX_LOG_LUMINANCE
                    - AutoExposureMath.MIN_LOG_LUMINANCE)
                    / AutoExposureMath.BIN_COUNT
                    * 0.5F;

    @Test
    void histogramUsesRec2020LuminanceAndRejectsNonFinitePixels() {
        OptionalInt red = AutoExposureMath.histogramBin(1.0F, 0.0F, 0.0F);
        OptionalInt green = AutoExposureMath.histogramBin(0.0F, 1.0F, 0.0F);
        OptionalInt blue = AutoExposureMath.histogramBin(0.0F, 0.0F, 1.0F);

        assertTrue(red.isPresent());
        assertTrue(green.isPresent());
        assertTrue(blue.isPresent());
        assertTrue(green.getAsInt() > red.getAsInt());
        assertTrue(red.getAsInt() > blue.getAsInt());
        assertEquals(0, AutoExposureMath.histogramBin(0.0F, 0.0F, 0.0F)
                .orElseThrow());
        assertFalse(AutoExposureMath.histogramBin(
                Float.NaN, 0.0F, 0.0F).isPresent());
        assertFalse(AutoExposureMath.histogramBin(
                Float.POSITIVE_INFINITY, 0.0F, 0.0F).isPresent());
    }

    @Test
    void diffuseAlbedoCorrectionBlendsRawAndNeutralizedLuminance() {
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

        assertTrue(reference < white);
        assertTrue(uncorrectedWhite > white);
        assertTrue(white < snow);
        assertTrue(snow < uncorrectedWhite);
        assertTrue(dark < reference);
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
    void albedoCorrectionBlendsRawAndProtectedNeutralizedLuminance() {
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
                1.3771F,
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
    void robustMeterDiscardsTheDarkestAndBrightestPercent() {
        int[] histogram = new int[AutoExposureMath.BIN_COUNT];
        histogram[0] = 1;
        histogram[binForGray(AutoExposureMath.KEY_LUMINANCE)] = 98;
        histogram[AutoExposureMath.BIN_COUNT - 1] = 1;

        AutoExposureMath.State result = AutoExposureMath.update(
                AutoExposureMath.State.initial(),
                histogram,
                0.0F,
                true,
                false);

        assertEquals(
                AutoExposureMath.BASELINE_EV,
                result.targetEv(),
                BIN_HALF_WIDTH_EV);
        assertEquals(result.targetEv(), result.exposureEv());
    }

    @Test
    void baselineAddsHalfEvAndExposureUsesBothBounds() {
        AutoExposureMath.State day = instantForGray(0.08F);
        AutoExposureMath.State dark = instantForGray(
                Math.scalb(AutoExposureMath.KEY_LUMINANCE, -8));
        AutoExposureMath.State middle = instantForGray(
                AutoExposureMath.KEY_LUMINANCE);
        AutoExposureMath.State bright = instantForGray(
                Math.scalb(AutoExposureMath.KEY_LUMINANCE, 8));

        assertEquals(1.5F, day.targetEv(), BIN_HALF_WIDTH_EV);
        assertEquals(4.0F, dark.targetEv());
        assertEquals(
                AutoExposureMath.BASELINE_EV,
                middle.targetEv(),
                BIN_HALF_WIDTH_EV);
        assertEquals(-4.0F, bright.targetEv());
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
                Math.scalb(AutoExposureMath.KEY_LUMINANCE, -4));
        AutoExposureMath.State brightening = AutoExposureMath.update(
                zero,
                histogramForGray(Math.scalb(
                        AutoExposureMath.KEY_LUMINANCE, -4)),
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
                AutoExposureMath.KEY_LUMINANCE);
        AutoExposureMath.State darkening = AutoExposureMath.update(
                fourEv,
                histogramForGray(AutoExposureMath.KEY_LUMINANCE),
                AutoExposureMath.DARKEN_T90_SECONDS,
                false,
                false);
        assertEquals(
                brightTarget.targetEv()
                        + (4.0F - brightTarget.targetEv()) * 0.1F,
                darkening.exposureEv(),
                2.0e-3F);
    }

    private static AutoExposureMath.State instantForGray(float luminance) {
        return AutoExposureMath.update(
                AutoExposureMath.State.initial(),
                histogramForGray(luminance),
                0.0F,
                true,
                true);
    }

    private static int[] histogramForGray(float luminance) {
        int[] histogram = new int[AutoExposureMath.BIN_COUNT];
        histogram[binForGray(luminance)] = 100;
        return histogram;
    }

    private static int binForGray(float luminance) {
        return AutoExposureMath.histogramBin(
                luminance, luminance, luminance).orElseThrow();
    }
}
