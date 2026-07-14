package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class AccumulationStateTest {
    @Test
    void stableFramesAdvanceWithoutReset() {
        AccumulationState state = new AccumulationState();
        FrameCamera camera = camera(1.0);
        assertTrue(state.prepare(camera, 7L, 3L, 11L, 13L, false));
        int epoch = state.epoch();
        state.submitted(camera, 11L, 13L);
        assertEquals(1, state.sampleIndex());
        assertFalse(state.prepare(camera, 7L, 3L, 11L, 13L, false));
        assertEquals(epoch, state.epoch());
    }

    @Test
    void sceneCameraAtlasAndExplicitInvalidationResetHistory() {
        AccumulationState state = new AccumulationState();
        FrameCamera camera = camera(1.0);
        state.prepare(camera, 1L, 1L, 2L, 3L, false);
        state.submitted(camera, 2L, 3L);
        assertTrue(state.prepare(camera, 2L, 2L, 2L, 3L, false));
        state.submitted(camera, 2L, 3L);
        assertTrue(state.prepare(camera(2.0), 2L, 2L, 2L, 3L, false));
        state.submitted(camera(2.0), 2L, 3L);
        assertTrue(state.prepare(camera(2.0), 2L, 2L, 4L, 3L, false));
        state.submitted(camera(2.0), 4L, 3L);
        assertTrue(state.prepare(camera(2.0), 2L, 2L, 4L, 3L, true));
        assertEquals(0, state.sampleIndex());
    }

    @Test
    void streamingAdditionsUseBoundedHistoryThenRestartAfterQuiescence() {
        AccumulationState state = new AccumulationState();
        FrameCamera camera = camera(1.0);
        state.prepare(camera, 1L, 1L, 2L, 3L, false);
        state.submitted(camera, 2L, 3L);
        for (long revision = 2L; revision <= 20L; revision++) {
            assertFalse(state.prepare(camera, revision, 1L, 2L, 3L, false));
            state.submitted(camera, 2L, 3L);
            assertTrue(state.sampleIndex() < 8);
        }

        boolean reset = false;
        for (int frame = 0; frame < 8; frame++) {
            reset = state.prepare(camera, 20L, 1L, 2L, 3L, false);
            if (!reset) {
                state.submitted(camera, 2L, 3L);
            }
        }
        assertTrue(reset);
        assertEquals(0, state.sampleIndex());
    }

    private static FrameCamera camera(double x) {
        return new FrameCamera(new Matrix4f(), x, 2.0, 3.0);
    }
}
