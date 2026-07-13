package dev.prime.render.terrain;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

final class SectionGenerationTracker {
    private final Long2LongOpenHashMap generations = new Long2LongOpenHashMap();
    private long worldEpoch;

    long worldEpoch() {
        return this.worldEpoch;
    }

    long current(long sectionKey) {
        return this.generations.get(sectionKey);
    }

    long advance(long sectionKey) {
        long next = Math.addExact(this.current(sectionKey), 1L);
        this.generations.put(sectionKey, next);
        return next;
    }

    boolean isCurrent(long sectionKey, long token) {
        return this.current(sectionKey) == token;
    }

    boolean isCurrent(long epoch, long sectionKey, long token) {
        return this.worldEpoch == epoch && this.isCurrent(sectionKey, token);
    }

    void resetWorld() {
        this.worldEpoch = Math.addExact(this.worldEpoch, 1L);
        this.generations.clear();
    }
}
