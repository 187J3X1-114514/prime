package dev.prime.render.post.nrd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import dev.prime.render.post.SubmittedFrame;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class NrdFrameHistoryTest {
    private static final SunDirection NOON =
            new SunDirection(0.0F, 1.0F, 0.0F);

    @Test
    void onlyOnePlanMayBeOutstandingAndOnlyExecutionMaySubmitIt() {
        NrdFrameHistory history = new NrdFrameHistory();
        SubmittedFrame<NrdFramePlan> first =
                history.plan(input(camera(0.0), 1_000_000L));

        assertThrows(
                IllegalStateException.class,
                () -> history.plan(input(camera(1.0), 2_000_000L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> history.submitted(first));

        assertSame(first.plan(), first.claimForExecution());
        history.submitted(first);
        assertThrows(
                IllegalArgumentException.class,
                () -> history.submitted(first));
        assertThrows(
                IllegalArgumentException.class,
                first::claimForExecution);
    }

    @Test
    void submittedPlanAloneAdvancesTheNextHistoryVersion() {
        NrdFrameHistory history = new NrdFrameHistory();
        FrameCamera firstCamera = camera(0.0);
        SubmittedFrame<NrdFramePlan> first =
                history.plan(input(firstCamera, 1_000_000L));
        assertTrue(first.plan().restart());
        first.claimForExecution();
        history.submitted(first);

        SubmittedFrame<NrdFramePlan> second =
                history.plan(input(camera(1.0), 11_000_000L));
        assertFalse(second.plan().restart());
        assertSame(firstCamera, second.plan().historyCamera());
        assertEquals(1, second.plan().frameIndex());
        assertEquals(
                10.0F,
                second.plan().deltaMilliseconds(),
                1.0e-5F);
    }

    @Test
    void foreignHistoryCannotCommitAPlan() {
        NrdFrameHistory owner = new NrdFrameHistory();
        NrdFrameHistory foreign = new NrdFrameHistory();
        SubmittedFrame<NrdFramePlan> frame =
                owner.plan(input(camera(0.0), 1L));
        frame.claimForExecution();

        assertThrows(
                IllegalArgumentException.class,
                () -> foreign.submitted(frame));
    }

    @Test
    void abandoningConsumedWorkKeepsTheCommittedHistory() {
        NrdFrameHistory history = new NrdFrameHistory();
        SubmittedFrame<NrdFramePlan> abandoned =
                history.plan(input(camera(0.0), 1_000_000L));
        abandoned.claimForExecution();
        history.abandon(abandoned);

        assertThrows(
                IllegalArgumentException.class,
                abandoned::claimForExecution);
        assertThrows(
                IllegalArgumentException.class,
                () -> history.abandon(abandoned));

        SubmittedFrame<NrdFramePlan> retry =
                history.plan(input(camera(1.0), 2_000_000L));
        assertTrue(retry.plan().restart());
        assertEquals(0, retry.plan().frameIndex());
        assertSame(
                retry.plan().input().camera(),
                retry.plan().historyCamera());
    }

    private static NrdFrameInput input(
            FrameCamera camera, long frameTimeNanos) {
        return new NrdFrameInput(
                camera,
                frameTimeNanos,
                1L,
                2L,
                NOON,
                0.25F,
                -0.25F,
                false);
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
