package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class SectionMeshAccumulatorTest {
    @Test
    void buildTransfersOwnershipExactlyOnce() {
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        assertTrue(accumulator.build().isEmpty());
        assertThrows(IllegalStateException.class, accumulator::build);
    }
}
