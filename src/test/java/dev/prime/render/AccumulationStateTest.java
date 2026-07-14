package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class AccumulationStateTest {
    private static final SunDirection NOON = SunDirection.fromVanillaAngle(0.0F);

    @Test
    void stableFramesAdvanceWithoutReset() {
        AccumulationState state = new AccumulationState();
        FrameCamera camera = camera(1.0);
        assertTrue(state.prepare(camera, 3L, 11L, 13L, NOON, false));
        int epoch = state.epoch();
        state.submitted(camera, 11L, 13L, NOON);
        assertEquals(1, state.sampleIndex());
        assertFalse(state.prepare(camera, 3L, 11L, 13L, NOON, false));
        assertEquals(epoch, state.epoch());
    }

    @Test
    void worldCameraAtlasAndExplicitInvalidationResetHistory() {
        AccumulationState state = new AccumulationState();
        FrameCamera camera = camera(1.0);
        state.prepare(camera, 1L, 2L, 3L, NOON, false);
        state.submitted(camera, 2L, 3L, NOON);
        assertTrue(state.prepare(camera, 2L, 2L, 3L, NOON, false));
        state.submitted(camera, 2L, 3L, NOON);
        assertTrue(state.prepare(camera(2.0), 2L, 2L, 3L, NOON, false));
        state.submitted(camera(2.0), 2L, 3L, NOON);
        assertTrue(state.prepare(camera(2.0), 2L, 4L, 3L, NOON, false));
        state.submitted(camera(2.0), 4L, 3L, NOON);
        assertTrue(state.prepare(camera(2.0), 2L, 4L, 3L, NOON, true));
        assertEquals(0, state.sampleIndex());
    }

    @Test
    void gradualSunMotionUsesBoundedHistoryWithoutRestarting() {
        AccumulationState state = new AccumulationState();
        FrameCamera camera = camera(1.0);
        state.prepare(camera, 1L, 2L, 3L, NOON, false);
        state.submitted(camera, 2L, 3L, NOON);
        SunDirection movingSun = NOON;
        for (int step = 1; step <= 20; step++) {
            movingSun = SunDirection.fromVanillaAngle((float) Math.toRadians(step * 0.01));
            assertFalse(state.prepare(camera, 1L, 2L, 3L, movingSun, false));
            state.submitted(camera, 2L, 3L, movingSun);
            assertTrue(state.sampleIndex() < 8);
        }

        for (int frame = 0; frame < 8; frame++) {
            assertFalse(state.prepare(camera, 1L, 2L, 3L, movingSun, false));
            state.submitted(camera, 2L, 3L, movingSun);
        }
        assertTrue(state.sampleIndex() >= 8);
    }

    @Test
    void changingSunDirectionInvalidatesAccumulatedRadiance() {
        AccumulationState state = new AccumulationState();
        FrameCamera camera = camera(1.0);
        state.prepare(camera, 1L, 2L, 3L, NOON, false);
        state.submitted(camera, 2L, 3L, NOON);

        SunDirection sunset = SunDirection.fromVanillaAngle((float) (Math.PI * 0.5));
        assertTrue(state.prepare(camera, 1L, 2L, 3L, sunset, false));
        assertEquals(0, state.sampleIndex());
    }

    private static FrameCamera camera(double x) {
        return new FrameCamera(new Matrix4f(), x, 2.0, 3.0);
    }
}
