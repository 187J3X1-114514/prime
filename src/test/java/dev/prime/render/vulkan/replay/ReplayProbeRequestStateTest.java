package dev.prime.render.vulkan.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

final class ReplayProbeRequestStateTest {
    @Test
    void oneRequestCanBeClaimedAndCompletedExactlyOnce() {
        ReplayProbeRequestState<String> state =
                new ReplayProbeRequestState<>();
        var result = state.request(64, 64);
        ReplayProbeRequestState.Request<String> request = state.claim();

        assertEquals(64, request.width());
        assertEquals(64, request.height());
        assertSame(null, state.claim());

        state.complete(request, "complete");

        assertEquals("complete", result.join());
        assertFalse(result.isCompletedExceptionally());
        assertFalse(state.request(1, 1).isDone());
    }

    @Test
    void duplicateAndInvalidRequestsAreRejected() {
        ReplayProbeRequestState<String> state =
                new ReplayProbeRequestState<>();

        assertThrows(
                IllegalArgumentException.class,
                () -> state.request(0, 64));
        assertThrows(
                IllegalArgumentException.class,
                () -> state.request(257, 256));
        state.request(64, 64);
        assertThrows(
                IllegalStateException.class,
                () -> state.request(64, 64));
    }

    @Test
    void destructionFailsPendingRequestAndRejectsNewOnes() {
        ReplayProbeRequestState<String> state =
                new ReplayProbeRequestState<>();
        var result = state.request(64, 64);

        state.destroy();
        state.destroy();

        assertTrue(result.isCompletedExceptionally());
        assertThrows(CompletionException.class, result::join);
        assertThrows(
                IllegalStateException.class,
                () -> state.request(64, 64));
    }
}
