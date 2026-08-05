package dev.prime.render.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                input(firstCamera, 1_000_000L, 1L));
        FrameCamera retryCamera = camera(1.0);
        TemporalReconstructionState.Plan retry = state.plan(
                input(retryCamera, 11_000_000L, 1L));

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
                input(firstCamera, 1_000_000L, 1L));
        FrameCamera secondCamera = camera(1.0);
        TemporalReconstructionState.Plan second = first.committedState().plan(
                input(secondCamera, 11_000_000L, 1L));

        assertFalse(second.restart());
        assertFalse(second.cameraCut());
        assertEquals(1, second.frameIndex());
        assertSame(firstCamera, second.historyCamera());
        assertEquals(10.0F, second.deltaMilliseconds(), 1.0e-5F);
    }

    @Test
    void unrelatedSceneAndExplicitInvalidationRestart() {
        TemporalReconstructionState state = TemporalReconstructionState.initial()
                .plan(input(camera(0.0), 1L, 1L))
                .committedState();
        FrameCamera current = camera(1.0);

        TemporalReconstructionState.Plan unrelated = state.plan(
                input(current, 2L, 9L));
        assertTrue(unrelated.restart());
        assertEquals(0, unrelated.frameIndex());
        assertSame(current, unrelated.historyCamera());
        assertTrue(state.invalidated().plan(input(current, 2L, 1L)).restart());
        assertTrue(state.plan(new TemporalReconstructionState.Input(
                current, 2L, 1L, true)).restart());
    }

    @Test
    void renderOriginRelocationKeepsHistory() {
        TemporalReconstructionState state = TemporalReconstructionState.initial()
                .plan(input(camera(255.0), 1L, 1L))
                .committedState();

        TemporalReconstructionState.Plan relocated = state.plan(
                input(camera(257.0), 2L, 1L));

        assertFalse(relocated.restart());
        assertFalse(relocated.cameraCut());
        assertEquals(1, relocated.frameIndex());
    }

    @Test
    void largeCameraCutAndFrameDelayAreReported() {
        TemporalReconstructionState state = TemporalReconstructionState.initial()
                .plan(input(camera(0.0), 1L, 1L))
                .committedState();
        TemporalReconstructionState.Plan plan = state.plan(
                input(camera(64.0), 2_000_000_001L, 1L));

        assertTrue(plan.cameraCut());
        assertTrue(plan.restart());
        assertEquals(1000.0F, plan.deltaMilliseconds());
    }

    @Test
    void submittedFrameTimeCannotMoveBackwards() {
        TemporalReconstructionState state = TemporalReconstructionState.initial()
                .plan(input(camera(0.0), 2L, 1L))
                .committedState();

        assertThrows(
                IllegalArgumentException.class,
                () -> state.plan(input(camera(0.0), 1L, 1L)));
    }

    private static TemporalReconstructionState.Input input(
            FrameCamera camera, long time, long revision) {
        return new TemporalReconstructionState.Input(
                camera, time, revision, false);
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
