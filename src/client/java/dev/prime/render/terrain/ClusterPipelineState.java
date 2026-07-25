package dev.prime.render.terrain;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Render-thread-owned generation state across the terrain request pipeline.
 *
 * <p>A logical generation may occupy at most one of queued, in-flight or ready. A newer
 * generation may queue behind older in-flight/ready work; generation checks make the older result
 * stale without discarding the replacement.
 */
final class ClusterPipelineState {
    private static final long ABSENT = Long.MIN_VALUE;

    private final Long2LongOpenHashMap queued = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap inFlight = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap ready = new Long2LongOpenHashMap();

    boolean enqueue(long key, long generation) {
        if (isQueued(key, generation)
                || isInFlight(key, generation)
                || isReady(key, generation)) {
            return false;
        }
        this.queued.put(key, generation);
        return true;
    }

    boolean isQueued(long key, long generation) {
        return this.queued.getOrDefault(key, ABSENT) == generation;
    }

    void cancelQueued(long key, long generation) {
        if (isQueued(key, generation)) {
            this.queued.remove(key);
        }
    }

    void clearQueued() {
        this.queued.clear();
    }

    boolean hasInFlight(long key) {
        return this.inFlight.containsKey(key);
    }

    boolean isInFlight(long key, long generation) {
        return this.inFlight.getOrDefault(key, ABSENT) == generation;
    }

    void beginInFlight(long key, long generation) {
        if (hasInFlight(key) || isReady(key, generation)) {
            throw new IllegalStateException("Cluster generation entered two pipeline stages");
        }
        cancelQueued(key, generation);
        this.inFlight.put(key, generation);
    }

    void cancelInFlight(long key, long generation) {
        if (isInFlight(key, generation)) {
            this.inFlight.remove(key);
        }
    }

    boolean completeToReady(long key, long generation) {
        cancelInFlight(key, generation);
        cancelQueued(key, generation);
        if (isReady(key, generation)) {
            return false;
        }
        this.ready.put(key, generation);
        return true;
    }

    boolean isReady(long key, long generation) {
        return this.ready.getOrDefault(key, ABSENT) == generation;
    }

    void consumeReady(long key, long generation) {
        if (isReady(key, generation)) {
            this.ready.remove(key);
        }
    }

    void clear() {
        this.queued.clear();
        this.inFlight.clear();
        this.ready.clear();
    }
}
