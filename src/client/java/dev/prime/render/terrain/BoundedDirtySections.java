package dev.prime.render.terrain;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;

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

    /**
     * Adds every section touched by the block range after expanding it by one block on all six
     * faces. The expansion is required because a block model at a section boundary can depend on
     * its neighbour. Oversized ranges collapse directly to a full invalidation instead of
     * spending unbounded time enumerating section coordinates.
     */
    synchronized void addExpandedBlockRange(
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ) {
        if (this.fullInvalidation) {
            return;
        }
        int minimumSectionX = expandedMinimumSection(minimumX, maximumX);
        int minimumSectionY = expandedMinimumSection(minimumY, maximumY);
        int minimumSectionZ = expandedMinimumSection(minimumZ, maximumZ);
        int maximumSectionX = expandedMaximumSection(minimumX, maximumX);
        int maximumSectionY = expandedMaximumSection(minimumY, maximumY);
        int maximumSectionZ = expandedMaximumSection(minimumZ, maximumZ);
        long sectionCountX = (long) maximumSectionX - minimumSectionX + 1L;
        long sectionCountY = (long) maximumSectionY - minimumSectionY + 1L;
        long sectionCountZ = (long) maximumSectionZ - minimumSectionZ + 1L;
        if (sectionCountX > this.maximumKeys
                || sectionCountY > this.maximumKeys
                || sectionCountZ > this.maximumKeys
                || sectionCountX * sectionCountY > this.maximumKeys
                || sectionCountX * sectionCountY * sectionCountZ > this.maximumKeys) {
            this.invalidateAll();
            return;
        }
        for (int z = minimumSectionZ; z <= maximumSectionZ; z++) {
            for (int y = minimumSectionY; y <= maximumSectionY; y++) {
                for (int x = minimumSectionX; x <= maximumSectionX; x++) {
                    this.keys.add(SectionPos.asLong(x, y, z));
                    if (this.keys.size() > this.maximumKeys) {
                        this.invalidateAll();
                        return;
                    }
                }
            }
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

    private static int expandedMinimumSection(int firstBlock, int secondBlock) {
        return (int) Math.floorDiv((long) Math.min(firstBlock, secondBlock) - 1L, 16L);
    }

    private static int expandedMaximumSection(int firstBlock, int secondBlock) {
        return (int) Math.floorDiv((long) Math.max(firstBlock, secondBlock) + 1L, 16L);
    }

    record Batch(boolean fullInvalidation, long[] keys) {
    }
}
