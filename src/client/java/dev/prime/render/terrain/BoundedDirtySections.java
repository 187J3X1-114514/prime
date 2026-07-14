package dev.prime.render.terrain;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** A bounded, coalescing cross-thread invalidation channel. */
final class BoundedDirtySections {
    private final int maximumKeys;
    private final LongOpenHashSet keys = new LongOpenHashSet();
    private boolean fullInvalidation;

    BoundedDirtySections(int maximumKeys) {
        if (maximumKeys <= 0) {
            throw new IllegalArgumentException("Dirty-section capacity must be positive");
        }
        this.maximumKeys = maximumKeys;
    }

    synchronized void add(long key) {
        if (this.fullInvalidation) {
            return;
        }
        this.keys.add(key);
        if (this.keys.size() > this.maximumKeys) {
            this.keys.clear();
            this.fullInvalidation = true;
        }
    }

    synchronized void invalidateAll() {
        this.keys.clear();
        this.fullInvalidation = true;
    }

    synchronized Batch drain() {
        Batch result = new Batch(this.fullInvalidation, this.keys.toLongArray());
        this.keys.clear();
        this.fullInvalidation = false;
        return result;
    }

    synchronized void clear() {
        this.keys.clear();
        this.fullInvalidation = false;
    }

    record Batch(boolean fullInvalidation, long[] keys) {
    }
}
