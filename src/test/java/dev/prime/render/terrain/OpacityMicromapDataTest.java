package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class OpacityMicromapDataTest {
    @Test
    void birdCurveMapsEverySubdivisionCellExactlyOnce() {
        boolean[] seen = new boolean[OpacityMicromapData.MICRO_TRIANGLE_COUNT];
        float[] barycentric = new float[3];
        for (int cell = 0; cell < OpacityMicromapData.MICRO_TRIANGLE_COUNT; cell++) {
            EmissionDistribution.cell(cell).samplePoint(0, barycentric);
            int index = OpacityMicromapData.barycentricsToSpaceFillingCurveIndex(
                    barycentric[1],
                    barycentric[2],
                    OpacityMicromapData.SUBDIVISION_LEVEL);
            assertTrue(index >= 0 && index < seen.length);
            assertFalse(seen[index], "Two microtriangles mapped to bird index " + index);
            seen[index] = true;
        }
        for (boolean mapped : seen) {
            assertTrue(mapped);
        }
    }

    @Test
    void fullyUnknownFallbackPreservesOneIndexPerCutoutTriangle() {
        OpacityMicromapData data = OpacityMicromapData.fullyUnknown(17);
        assertEquals(0, data.blockCount());
        assertEquals(17, data.triangleIndices().length);
        assertEquals(17L * Integer.BYTES, data.byteSize());
    }
}
