package dev.prime.render.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

final class BoundedDirtySectionsTest {
    @Test
    void duplicateKeysAreCoalesced() {
        BoundedDirtySections dirty = new BoundedDirtySections(2);
        dirty.add(7L);
        dirty.add(7L);
        BoundedDirtySections.Batch batch = dirty.drain();
        assertFalse(batch.fullInvalidation());
        assertArrayEquals(new long[] {7L}, batch.keys());
    }

    @Test
    void overflowCollapsesToOneFullInvalidation() {
        BoundedDirtySections dirty = new BoundedDirtySections(2);
        dirty.add(1L);
        dirty.add(2L);
        dirty.add(3L);
        dirty.add(4L);
        BoundedDirtySections.Batch batch = dirty.drain();
        assertTrue(batch.fullInvalidation());
        assertArrayEquals(new long[0], batch.keys());
        assertFalse(dirty.drain().fullInvalidation());
    }

    @Test
    void explicitFullInvalidationSupersedesKeys() {
        BoundedDirtySections dirty = new BoundedDirtySections(4);
        dirty.add(9L);
        dirty.invalidateAll();
        BoundedDirtySections.Batch batch = dirty.drain();
        Arrays.sort(batch.keys());
        assertTrue(batch.fullInvalidation());
        assertArrayEquals(new long[0], batch.keys());
    }

    @Test
    void blockInvalidationExpandsAcrossAllSectionFaces() {
        BoundedDirtySections dirty = new BoundedDirtySections(16);
        dirty.addExpandedBlockRange(15, 15, 15, 15, 15, 15);
        long[] actual = dirty.drain().keys();
        Arrays.sort(actual);
        long[] expected = new long[] {
            SectionPos.asLong(0, 0, 0),
            SectionPos.asLong(1, 0, 0),
            SectionPos.asLong(0, 1, 0),
            SectionPos.asLong(1, 1, 0),
            SectionPos.asLong(0, 0, 1),
            SectionPos.asLong(1, 0, 1),
            SectionPos.asLong(0, 1, 1),
            SectionPos.asLong(1, 1, 1)
        };
        Arrays.sort(expected);
        assertArrayEquals(expected, actual);
    }

    @Test
    void reversedAndNegativeBlockRangesUseFloorDivision() {
        BoundedDirtySections dirty = new BoundedDirtySections(16);
        dirty.addExpandedBlockRange(0, 0, 0, -1, -1, -1);
        long[] actual = dirty.drain().keys();
        Arrays.sort(actual);
        long[] expected = new long[] {
            SectionPos.asLong(-1, -1, -1),
            SectionPos.asLong(0, -1, -1),
            SectionPos.asLong(-1, 0, -1),
            SectionPos.asLong(0, 0, -1),
            SectionPos.asLong(-1, -1, 0),
            SectionPos.asLong(0, -1, 0),
            SectionPos.asLong(-1, 0, 0),
            SectionPos.asLong(0, 0, 0)
        };
        Arrays.sort(expected);
        assertArrayEquals(expected, actual);
    }

    @Test
    void oversizedBlockRangeCollapsesWithoutEnumeration() {
        BoundedDirtySections dirty = new BoundedDirtySections(2);
        dirty.addExpandedBlockRange(-1000, 0, 0, 1000, 0, 0);
        BoundedDirtySections.Batch batch = dirty.drain();
        assertTrue(batch.fullInvalidation());
        assertArrayEquals(new long[0], batch.keys());
    }
}
