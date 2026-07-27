package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.post.PostProcessingMode;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class ScreenshotFramePlanTest {
    @Test
    void sobolIndexAndEpochAreDerivedBeforeDeviceExecution() {
        ScreenshotFramePlan lastFirstEpoch = input(0xffffL).plan();
        ScreenshotFramePlan firstSecondEpoch = input(0x1_0000L).plan();

        assertEquals(0xffff, lastFirstEpoch.integrator().sampleIndex());
        assertEquals(0, lastFirstEpoch.integrator().sampleEpoch());
        assertEquals(0, firstSecondEpoch.integrator().sampleIndex());
        assertEquals(1, firstSecondEpoch.integrator().sampleEpoch());
        assertEquals(0x1_0001L, firstSecondEpoch.nextSampleCount());
    }

    @Test
    void screenshotPlanPreservesNativeUnfilteredIntegratorContract() {
        ScreenshotFrameInput input = input(7L);
        ScreenshotFramePlan first = input.plan();
        ScreenshotFramePlan second = input.plan();

        assertEquals(first, second);
        assertEquals(PostProcessingMode.DISABLED,
                first.integrator().postProcessingMode());
        assertEquals(0, first.integrator().jitterPhase());
        assertEquals(0, first.integrator().packedRayCone() >>> 16);
        assertFalse(first.integrator().shInput());
        assertFalse(first.integrator().rawNumericalDiagnostic());
        assertFalse(first.integrator().triangleDebug());
        first.requireSceneRevision(input.sceneRevision());
        assertThrows(
                IllegalStateException.class,
                () -> first.requireSceneRevision(input.sceneRevision() + 1L));
        first.requireTextureRevision(input.textureRevision());
        assertThrows(
                IllegalStateException.class,
                () -> first.requireTextureRevision(input.textureRevision() + 1L));
    }

    @Test
    void invalidOrUnrepresentableSampleCountsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> input(-1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> input(1L << 47).plan());
    }

    private static ScreenshotFrameInput input(long sampleCount) {
        return new ScreenshotFrameInput(
                new FrameCamera(new Matrix4f(), 1.0, 2.0, 3.0),
                64,
                48,
                7L,
                11L,
                new SunDirection(0.0F, 1.0F, 0.0F),
                false,
                new LightingSettings.Snapshot(
                        0, 0, 0, 1L),
                new MaterialSettings.Snapshot(90, 1L),
                sampleCount,
                1.0F);
    }
}
