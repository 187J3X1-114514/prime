package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ClusterPipelineStateTest {
    @Test
    void oneGenerationCannotBeQueuedWhileItIsInFlightOrReady() {
        ClusterPipelineState state = new ClusterPipelineState();
        long key = 17L;
        long generation = 3L;

        assertTrue(state.enqueue(key, generation));
        state.beginInFlight(key, generation);
        assertFalse(state.enqueue(key, generation));
        assertTrue(state.completeToReady(key, generation));
        assertFalse(state.enqueue(key, generation));
        assertFalse(state.completeToReady(key, generation));

        state.consumeReady(key, generation);
        assertTrue(state.enqueue(key, generation));
    }

    @Test
    void newerGenerationMayQueueBehindOlderWork() {
        ClusterPipelineState state = new ClusterPipelineState();
        long key = 23L;

        assertTrue(state.enqueue(key, 4L));
        state.beginInFlight(key, 4L);
        assertTrue(state.enqueue(key, 5L));
        state.cancelInFlight(key, 4L);

        assertTrue(state.isQueued(key, 5L));
        assertFalse(state.isQueued(key, 4L));
    }

    @Test
    void cancelledWorkerReleasesInFlightAndRequeuesTheSameGeneration() {
        ClusterPipelineState state = new ClusterPipelineState();
        long key = 29L;
        long generation = 7L;

        assertTrue(state.enqueue(key, generation));
        state.beginInFlight(key, generation);
        state.cancelInFlight(key, generation);

        assertFalse(state.hasInFlight(key));
        assertTrue(state.enqueue(key, generation));
        assertTrue(state.isQueued(key, generation));
    }
}
