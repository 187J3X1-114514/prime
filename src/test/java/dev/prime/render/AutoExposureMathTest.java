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

        assertEquals(0.0F, result.targetEv(), BIN_HALF_WIDTH_EV);
        assertEquals(result.targetEv(), result.exposureEv());
    }

    @Test
    void dayBaselineIsOneEvAndBrightScenesCannotBeDarkened() {
        AutoExposureMath.State day = instantForGray(0.08F);
        AutoExposureMath.State dark = instantForGray(
                Math.scalb(AutoExposureMath.KEY_LUMINANCE, -8));
        AutoExposureMath.State middle = instantForGray(
                AutoExposureMath.KEY_LUMINANCE);
        AutoExposureMath.State bright = instantForGray(
                Math.scalb(AutoExposureMath.KEY_LUMINANCE, 8));

        assertEquals(1.0F, day.targetEv(), BIN_HALF_WIDTH_EV);
        assertEquals(4.0F, dark.targetEv());
        assertEquals(0.0F, middle.targetEv(), BIN_HALF_WIDTH_EV);
        assertEquals(0.0F, bright.targetEv());
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
