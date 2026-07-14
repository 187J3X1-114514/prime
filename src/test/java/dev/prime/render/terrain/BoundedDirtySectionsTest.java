package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
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
}
