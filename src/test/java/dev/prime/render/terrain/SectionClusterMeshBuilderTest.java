package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class SectionClusterMeshBuilderTest {
    @Test
    void keepsOpaqueBeforeCutoutBeforeTransmissionAndTranslatesSectionLocalPositions() {
        SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(-4, 0, 8);
        builder.add(-4, 0, 8, mesh(1.0F, 1, 1, 1));
        builder.add(-1, 2, 11, mesh(2.0F, 1, 1, 1));

        CpuSectionMesh result = builder.build();
        assertEquals(2, result.opaqueTriangleCount());
        assertEquals(2, result.cutoutTriangleCount());
        assertEquals(2, result.transmissiveTriangleCount());
        assertArrayEquals(new float[] {
            1, 1, 1, 2, 1, 1, 1, 2, 1,
            50, 34, 50, 51, 34, 50, 50, 35, 50,
            1, 1, 2, 2, 1, 2, 1, 2, 2,
            50, 34, 51, 51, 34, 51, 50, 35, 51,
            1, 1, 3, 2, 1, 3, 1, 2, 3,
            50, 34, 52, 51, 34, 52, 50, 35, 52
        }, result.positions());
        assertEquals(1, result.primitiveRecords()[0]);
        assertEquals(2, result.primitiveRecords()[8]);
        assertEquals(101, result.primitiveRecords()[16]);
        assertEquals(102, result.primitiveRecords()[24]);
        assertEquals(201, result.primitiveRecords()[32]);
        assertEquals(202, result.primitiveRecords()[40]);
    }

    @Test
    void rejectsDuplicateSectionsInsideOneVirtualChunk() {
        SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(0, 0, 0);
        builder.add(0, 0, 0, mesh(0.0F, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.add(0, 0, 0, mesh(0.0F, 1, 1, 1)));
    }

    private static CpuSectionMesh mesh(float base, int opaque, int cutout, int transmissive) {
        float[] positions = new float[] {
            base, base, base,
            base + 1, base, base,
            base, base + 1, base,
            base, base, base + 1,
            base + 1, base, base + 1,
            base, base + 1, base + 1,
            base, base, base + 2,
            base + 1, base, base + 2,
            base, base + 1, base + 2
        };
        int[] primitives = new int[(opaque + cutout + transmissive) * CpuSectionMesh.PRIMITIVE_WORDS];
        primitives[0] = (int) base;
        primitives[opaque * CpuSectionMesh.PRIMITIVE_WORDS] = (int) base + 100;
        primitives[(opaque + cutout) * CpuSectionMesh.PRIMITIVE_WORDS] = (int) base + 200;
        return new CpuSectionMesh(
                positions,
                primitives,
                opaque,
                cutout,
                transmissive,
                specialMicromap(cutout),
                CpuSectionLights.EMPTY);
    }

    private static OpacityMicromapData specialMicromap(int triangleCount) {
        return OpacityMicromapData.fullyUnknown(triangleCount);
    }
}
