package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RendererLifecycleTest {
    @Test
    void failureIsOwnedByTheLifecycleAndInactiveReloadIsAlreadyReady() {
        RendererLifecycle lifecycle = new RendererLifecycle();
        assertEquals(RuntimeState.UNAVAILABLE, lifecycle.state());
        assertTrue(lifecycle.beginResourceReload().ready().isDone());

        lifecycle.fail(new IllegalStateException("host rejected frame"));

        assertEquals(RuntimeState.FAILED, lifecycle.state());
        assertEquals("host rejected frame", lifecycle.failureReason());
    }
}
