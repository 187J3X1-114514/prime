package dev.prime.render.vulkan.nrd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class NrdTemporalStateTest {
    private static final SunDirection NOON = new SunDirection(0.0F, 1.0F, 0.0F);

    @Test
    void initialFrameRestartsFromItsOwnCamera() {
        FrameCamera camera = camera(0.0);
        NrdTemporalState.Plan plan = NrdTemporalState.initial().plan(
                input(camera, 1_000_000L, 1L, 2L, NOON, 0.25F, -0.25F, false));

        assertTrue(plan.restart());
        assertSame(camera, plan.historyCamera());
        assertEquals(0, plan.currentFrameIndex());
        assertEquals(0.25F, plan.historyJitterX());
        assertEquals(-0.25F, plan.historyJitterY());
        assertEquals(1000.0F / 60.0F, plan.deltaMilliseconds());
    }

    @Test
    void onlyCommittedStateAdvancesHistory() {
        NrdTemporalState initial = NrdTemporalState.initial();
        FrameCamera firstCamera = camera(0.0);
        NrdTemporalState.Plan first = initial.plan(
                input(firstCamera, 1_000_000L, 1L, 2L, NOON, 0.1F, 0.2F, false));
        FrameCamera secondCamera = camera(1.0);

        NrdTemporalState.Plan uncommitted = initial.plan(
                input(secondCamera, 11_000_000L, 1L, 2L, NOON, 0.3F, 0.4F, false));
        assertTrue(uncommitted.restart());
        assertSame(secondCamera, uncommitted.historyCamera());

        NrdTemporalState.Plan committed = first.committedState().plan(
                input(secondCamera, 11_000_000L, 1L, 2L, NOON, 0.3F, 0.4F, false));
        assertFalse(committed.restart());
        assertSame(firstCamera, committed.historyCamera());
        assertEquals(1, committed.currentFrameIndex());
        assertEquals(0.1F, committed.historyJitterX());
        assertEquals(0.2F, committed.historyJitterY());
        assertEquals(10.0F, committed.deltaMilliseconds(), 1.0e-5F);
    }

    @Test
    void everyTemporalIdentityChangeRestartsAtFrameZero() {
        FrameCamera firstCamera = camera(0.0);
        NrdTemporalState state = NrdTemporalState.initial().plan(
                input(firstCamera, 1L, 1L, 2L, NOON, 0.0F, 0.0F, false))
                .committedState();
        FrameCamera currentCamera = camera(1.0);
        NrdFrameInput[] discontinuities = {
            input(currentCamera, 2L, 1L, 2L, NOON, 0.0F, 0.0F, true),
            input(currentCamera, 2L, 9L, 2L, NOON, 0.0F, 0.0F, false),
            input(currentCamera, 2L, 1L, 9L, NOON, 0.0F, 0.0F, false),
            input(
                    currentCamera,
                    2L,
                    1L,
                    2L,
                    new SunDirection(1.0F, 0.0F, 0.0F),
                    0.0F,
                    0.0F,
                    false)
        };

        for (NrdFrameInput input : discontinuities) {
            NrdTemporalState.Plan plan = state.plan(input);
            assertTrue(plan.restart());
            assertEquals(0, plan.currentFrameIndex());
            assertSame(currentCamera, plan.historyCamera());
        }
    }

    @Test
    void frameDeltaIsCappedAndInvalidJitterIsRejected() {
        NrdTemporalState state = NrdTemporalState.initial().plan(
                input(camera(0.0), 1L, 1L, 2L, NOON, 0.0F, 0.0F, false))
                .committedState();
        NrdTemporalState.Plan late = state.plan(
                input(camera(1.0), 2_000_000_001L, 1L, 2L, NOON, 0.0F, 0.0F, false));

        assertEquals(1000.0F, late.deltaMilliseconds());
        assertThrows(
                IllegalArgumentException.class,
                () -> input(
                        camera(0.0),
                        1L,
                        1L,
                        2L,
                        NOON,
                        Float.NaN,
                        0.0F,
                        false));
    }

    @Test
    void semanticPlanPreservesTheCompleteInputAndDerivedHistory() {
        FrameCamera camera = camera(4.0);
        NrdFrameInput input = new NrdFrameInput(
                camera,
                123L,
                7L,
                11L,
                NOON,
                0.25F,
                -0.5F,
                true,
                NrdDiagnostics.Mode.REPROJECTION_ERROR);
        NrdTemporalState.Plan transition =
                NrdTemporalState.initial().plan(input);
        NrdFramePlan plan = transition.semanticPlan(input);

        assertSame(input, plan.input());
        assertSame(camera, plan.historyCamera());
        assertEquals(0.25F, plan.historyJitterX());
        assertEquals(-0.5F, plan.historyJitterY());
        assertEquals(0, plan.frameIndex());
        assertTrue(plan.restart());
        assertEquals(1000.0F / 60.0F, plan.deltaMilliseconds());
    }

    private static NrdFrameInput input(
            FrameCamera camera,
            long time,
            long revision,
            long textureRevision,
            SunDirection sun,
            float jitterX,
            float jitterY,
            boolean forceRestart) {
        return new NrdFrameInput(
                camera,
                time,
                revision,
                textureRevision,
                sun,
                jitterX,
                jitterY,
                forceRestart,
                NrdDiagnostics.Mode.OFF);
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
