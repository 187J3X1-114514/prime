package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.terrain.CpuSectionMesh;
import org.junit.jupiter.api.Test;

final class PreparedBlasTest {
    private static final long POSITION_ADDRESS = 0x1_0000_0000L;

    @Test
    void oneBlasAcceptsLargeClusterCountsUntilARealAbiBoundary() {
        long seventyGibTriangles = (70L << 30)
                / (9L * Float.BYTES
                        + (long) CpuSectionMesh.PRIMITIVE_WORDS * Integer.BYTES);
        assertEquals(
                seventyGibTriangles,
                PreparedBlas.validateCounts(seventyGibTriangles, 0L, 0L, -1L));
        assertThrows(
                IllegalStateException.class,
                () -> PreparedBlas.validateCounts(
                        seventyGibTriangles, 0L, 0L, seventyGibTriangles - 1L));
        assertThrows(
                IllegalStateException.class,
                () -> PreparedBlas.validateCounts(
                        0x1_0000_0000L / 3L + 1L, 0L, 0L, -1L));
    }

    @Test
    void cutoutGeometryStartsAfterOpaqueVertices() {
        assertEquals(
                POSITION_ADDRESS + 12L * 3L * 3L * Float.BYTES,
                PreparedBlas.cutoutGeometryVertexAddress(POSITION_ADDRESS, 12, 5));
    }

    @Test
    void emptyCutoutGeometryKeepsAnAddressInsideThePositionBuffer() {
        assertEquals(
                POSITION_ADDRESS,
                PreparedBlas.cutoutGeometryVertexAddress(POSITION_ADDRESS, 12, 0));
    }

    @Test
    void cutoutOnlyGeometryStartsAtTheBeginningOfThePositionBuffer() {
        assertEquals(
                POSITION_ADDRESS,
                PreparedBlas.cutoutGeometryVertexAddress(POSITION_ADDRESS, 0, 5));
    }

    @Test
    void rejectsNegativeTriangleCounts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PreparedBlas.cutoutGeometryVertexAddress(POSITION_ADDRESS, -1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> PreparedBlas.cutoutGeometryVertexAddress(POSITION_ADDRESS, 0, -1));
    }

    @Test
    void transmissiveGeometryStartsAfterOpaqueAndCutoutPartitions() {
        long expected = POSITION_ADDRESS + (12L + 5L) * 9L * Float.BYTES;
        assertEquals(
                expected,
                PreparedBlas.transmissiveGeometryVertexAddress(
                        POSITION_ADDRESS, 12, 5, 7));
    }

    @Test
    void emptyTransmissiveGeometryUsesLiveBufferBase() {
        assertEquals(
                POSITION_ADDRESS,
                PreparedBlas.transmissiveGeometryVertexAddress(
                        POSITION_ADDRESS, 12, 5, 0));
    }
}
