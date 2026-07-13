package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class PreparedBlasTest {
    private static final long POSITION_ADDRESS = 0x1_0000_0000L;

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
}
