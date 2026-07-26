package dev.prime.render.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.FrameCamera;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class TemporalReconstructionStateTest {
    @Test
    void initialPlanRestartsWithoutMutatingTheSourceState() {
        TemporalReconstructionState state = TemporalReconstructionState.initial();
        FrameCamera firstCamera = camera(0.0);
        TemporalReconstructionState.Plan first = state.plan(
                input(firstCamera, 1_000_000L, 1L, 2L));
        FrameCamera retryCamera = camera(1.0);
        TemporalReconstructionState.Plan retry = state.plan(
                input(retryCamera, 11_000_000L, 1L, 2L));

        assertTrue(first.restart());
        assertEquals(0, first.frameIndex());
        assertSame(firstCamera, first.historyCamera());
        assertEquals(1000.0F / 60.0F, first.deltaMilliseconds());
        assertTrue(retry.restart());
        assertEquals(0, retry.frameIndex());
        assertSame(retryCamera, retry.historyCamera());
    }

    @Test
    void onlyCommittedPlansAdvanceHistory() {
        FrameCamera firstCamera = camera(0.0);
        TemporalReconstructionState.Plan first = TemporalReconstructionState.initial().plan(
                input(firstCamera, 1_000_000L, 1L, 2L));
        FrameCamera secondCamera = camera(1.0);
        TemporalReconstructionState.Plan second = first.committedState().plan(
                input(secondCamera, 11_000_000L, 1L, 2L));

        assertFalse(second.restart());
        assertFalse(second.cameraCut());
        assertEquals(1, second.frameIndex());
        assertSame(firstCamera, second.historyCamera());
        assertEquals(10.0F, second.deltaMilliseconds(), 1.0e-5F);
    }

    @Test
    void everyTemporalIdentityChangeAndExplicitInvalidationRestart() {
        TemporalReconstructionState state = TemporalReconstructionState.initial()
                .plan(input(camera(0.0), 1L, 1L, 2L))
                .committedState();
        FrameCamera current = camera(1.0);
        TemporalReconstructionState.Input[] discontinuities = {
            input(current, 2L, 9L, 2L),
            input(current, 2L, 1L, 9L)
        };

        for (TemporalReconstructionState.Input input : discontinuities) {
            TemporalReconstructionState.Plan plan = state.plan(input);
            assertTrue(plan.restart());
            assertEquals(0, plan.frameIndex());
            assertSame(current, plan.historyCamera());
        }
        assertTrue(state.invalidated().plan(
                input(current, 2L, 1L, 2L)).restart());
        assertTrue(state.plan(new TemporalReconstructionState.Input(
                current, 2L, 1L, 2L, true)).restart());
    }

    @Test
    void largeCameraCutAndFrameDelayAreReported() {
        TemporalReconstructionState state = TemporalReconstructionState.initial()
                .plan(input(camera(0.0), 1L, 1L, 2L))
                .committedState();
        TemporalReconstructionState.Plan plan = state.plan(
                input(camera(64.0), 2_000_000_001L, 1L, 2L));

        assertTrue(plan.cameraCut());
        assertTrue(plan.restart());
        assertEquals(1000.0F, plan.deltaMilliseconds());
    }

    private static TemporalReconstructionState.Input input(
            FrameCamera camera,
            long time,
            long revision,
            long textureRevision) {
        return new TemporalReconstructionState.Input(
                camera, time, revision, textureRevision, false);
    }

    private static FrameCamera camera(double x) {
        return new FrameCamera(
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f(),
                x,
                0.0,
                0.0,
                x,
                0.0,
                0.0);
    }
}
