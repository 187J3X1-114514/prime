package dev.prime.render.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.FrameCamera;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class ReconstructionFrameHistoryTest {
    @Test
    void outstandingFrameIsSingleUseAndMustExecuteBeforeSubmission() {
        ReconstructionFrameHistory history =
                new ReconstructionFrameHistory();
        ReconstructionFrameHistory.PlannedFrame frame =
                history.plan(input(camera(0.0), 1L, false));

        assertThrows(
                IllegalStateException.class,
                () -> history.plan(input(camera(1.0), 2L, false)));
        assertThrows(
                IllegalStateException.class,
                history::requestReset);
        assertThrows(
                IllegalArgumentException.class,
                () -> history.submitted(frame));

        assertSame(frame.plan(), frame.claimForExecution());
        history.submitted(frame);
        assertThrows(
                IllegalArgumentException.class,
                frame::claimForExecution);
        assertThrows(
                IllegalArgumentException.class,
                () -> history.submitted(frame));
    }

    @Test
    void onlySubmissionAdvancesAndExplicitResetStartsAtFrameZero() {
        ReconstructionFrameHistory history =
                new ReconstructionFrameHistory();
        FrameCamera firstCamera = camera(0.0);
        ReconstructionFrameHistory.PlannedFrame first =
                history.plan(input(firstCamera, 1_000_000L, false));
        assertTrue(first.plan().restart());
        first.claimForExecution();
        history.submitted(first);

        ReconstructionFrameHistory.PlannedFrame second =
                history.plan(input(camera(1.0), 11_000_000L, false));
        assertFalse(second.plan().restart());
        assertSame(firstCamera, second.plan().historyCamera());
        assertEquals(1, second.plan().frameIndex());
        second.claimForExecution();
        history.submitted(second);

        history.requestReset();
        ReconstructionFrameHistory.PlannedFrame reset =
                history.plan(input(camera(2.0), 21_000_000L, false));
        assertTrue(reset.plan().restart());
        assertEquals(0, reset.plan().frameIndex());
        assertSame(reset.plan().camera(), reset.plan().historyCamera());
    }

    @Test
    void foreignHistoryCannotCommitAFrame() {
        ReconstructionFrameHistory owner =
                new ReconstructionFrameHistory();
        ReconstructionFrameHistory foreign =
                new ReconstructionFrameHistory();
        ReconstructionFrameHistory.PlannedFrame frame =
                owner.plan(input(camera(0.0), 1L, false));
        frame.claimForExecution();

        assertThrows(
                IllegalArgumentException.class,
                () -> foreign.submitted(frame));
    }

    @Test
    void abandoningConsumedWorkKeepsTheCommittedHistory() {
        ReconstructionFrameHistory history =
                new ReconstructionFrameHistory();
        ReconstructionFrameHistory.PlannedFrame abandoned =
                history.plan(input(camera(0.0), 1_000_000L, false));
        abandoned.claimForExecution();
        history.abandon(abandoned);

        assertThrows(
                IllegalArgumentException.class,
                abandoned::claimForExecution);
        assertThrows(
                IllegalArgumentException.class,
                () -> history.abandon(abandoned));

        ReconstructionFrameHistory.PlannedFrame retry =
                history.plan(input(camera(1.0), 2_000_000L, false));
        assertTrue(retry.plan().restart());
        assertEquals(0, retry.plan().frameIndex());
        assertSame(retry.plan().camera(), retry.plan().historyCamera());
    }

    private static TemporalReconstructionState.Input input(
            FrameCamera camera,
            long frameTimeNanos,
            boolean forceRestart) {
        return new TemporalReconstructionState.Input(
                camera,
                frameTimeNanos,
                1L,
                forceRestart);
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
