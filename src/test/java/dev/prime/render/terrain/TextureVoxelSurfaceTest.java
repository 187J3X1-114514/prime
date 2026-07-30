package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

final class TextureVoxelSurfaceTest {
    @Test
    void bt601LumaMapsBlackAndWhiteToTheDeclaredOutwardRange() {
        assertEquals(0.0F, TextureVoxelMeshBuilder.heightFromArgb(0xff00_0000));
        assertEquals(
                1.0F / 32.0F,
                TextureVoxelMeshBuilder.heightFromArgb(0xffff_ffff));
        assertEquals(
                77.0F / 256.0F / 32.0F,
                TextureVoxelMeshBuilder.heightFromArgb(0xffff_0000),
                1.0E-7F);
        assertEquals(
                150.0F / 256.0F / 32.0F,
                TextureVoxelMeshBuilder.heightFromArgb(0xff00_ff00),
                1.0E-7F);
        assertEquals(
                29.0F / 256.0F / 32.0F,
                TextureVoxelMeshBuilder.heightFromArgb(0xff00_00ff),
                1.0E-7F);
    }

    @Test
    void detailWindowUsesTheInclusive128BlockSphere() {
        assertTrue(TerrainStreamer.clusterIntersectsDetailSphere(
                0.0F, 0.0F, 0.0F, 192.0F, 32.0F, 32.0F));
        assertFalse(TerrainStreamer.clusterIntersectsDetailSphere(
                0.0F, 0.0F, 0.0F, Math.nextUp(192.0F), 32.0F, 32.0F));
        assertFalse(TerrainStreamer.clusterIntersectsDetailSphere(
                0.0F, 0.0F, 0.0F, 160.0F, 160.0F, 160.0F));
    }

    @Test
    void equalHeightPixelsShareInternalWallsAndNeverMoveInward() {
        CpuVoxelMesh outward = TextureVoxelMeshBuilder.buildOpaqueHeightField(
                2,
                new int[] {
                    0xffff_ffff, 0xffff_ffff,
                    0xffff_ffff, 0xffff_ffff
                },
                2,
                1);
        CpuVoxelMesh inwardFacing = TextureVoxelMeshBuilder.buildOpaqueHeightField(
                2,
                new int[] {
                    0xffff_ffff, 0xffff_ffff,
                    0xffff_ffff, 0xffff_ffff
                },
                2,
                -1);

        // Four top quads plus eight outer-border wall quads. Equal neighbors add no wall.
        assertEquals(24, outward.triangleCount());
        assertEquals(0.0F, minimumAxis(outward.positions(), 2));
        assertEquals(1.0F / 32.0F, maximumAxis(outward.positions(), 2));
        assertEquals(-1.0F / 32.0F, minimumAxis(inwardFacing.positions(), 2));
        assertEquals(0.0F, maximumAxis(inwardFacing.positions(), 2));
        assertNondegenerate(outward.positions());
        assertNondegenerate(inwardFacing.positions());
    }

    @Test
    void aSingleRaisedPixelAddsOnlyItsVisibleStepWalls() {
        CpuVoxelMesh flat = TextureVoxelMeshBuilder.buildOpaqueHeightField(
                2,
                new int[] {
                    0xff00_0000, 0xff00_0000,
                    0xff00_0000, 0xff00_0000
                },
                1,
                1);
        CpuVoxelMesh stepped = TextureVoxelMeshBuilder.buildOpaqueHeightField(
                2,
                new int[] {
                    0xffff_ffff, 0xff00_0000,
                    0xff00_0000, 0xff00_0000
                },
                1,
                1);

        assertEquals(8, flat.triangleCount());
        // Four top quads and four walls around the one raised corner column.
        assertEquals(16, stepped.triangleCount());
        assertEquals(0.0F, minimumAxis(stepped.positions(), 1));
        assertEquals(1.0F / 32.0F, maximumAxis(stepped.positions(), 1));
        assertNondegenerate(stepped.positions());
    }

    @Test
    void compiledClusterRoundTripPreservesReusableMeshesAndInstances() {
        int triangleCount = 2;
        float[] positions = new float[triangleCount * 9];
        int[] primitives =
                new int[triangleCount * CpuSectionMesh.PRIMITIVE_WORDS];
        CpuVoxelMesh voxelMesh = new CpuVoxelMesh(
                positions,
                primitives,
                triangleCount,
                0,
                0,
                OpacityMicromapData.EMPTY);
        CpuVoxelInstances instances = new CpuVoxelInstances(
                new int[] {0, 0},
                new int[] {0x0012_3456, 0x00ab_cdef},
                new float[] {1.0F, 2.0F, 3.0F, 4.0F, 5.0F, 6.0F});
        CpuClusterMesh mesh = CpuClusterMesh.fromEncoded(
                List.of(),
                0L,
                0L,
                0L,
                OpacityMicromapData.EMPTY,
                CompiledClusterLights.EMPTY,
                List.of(voxelMesh),
                instances);
        CompiledCluster source = new CompiledCluster(
                SectionPos.asLong(0, 0, 0), 0, 0, 0, mesh);

        CompiledCluster decoded =
                CompiledClusterCodec.decode(CompiledClusterCodec.encode(source));

        assertEquals(1, decoded.mesh().voxelMeshes().size());
        assertEquals(2, decoded.mesh().voxelInstances().count());
        assertArrayEquals(
                instances.meshIndices(),
                decoded.mesh().voxelInstances().meshIndices());
        assertArrayEquals(
                instances.packedTints(),
                decoded.mesh().voxelInstances().packedTints());
        assertArrayEquals(
                instances.translations(),
                decoded.mesh().voxelInstances().translations());
        assertArrayEquals(
                positions,
                decoded.mesh().voxelMeshes().getFirst().positions());
        assertArrayEquals(
                primitives,
                decoded.mesh().voxelMeshes().getFirst().primitiveRecords());
    }

    private static float minimumAxis(float[] positions, int axis) {
        float result = Float.POSITIVE_INFINITY;
        for (int index = axis; index < positions.length; index += 3) {
            result = Math.min(result, positions[index]);
        }
        return result;
    }

    private static float maximumAxis(float[] positions, int axis) {
        float result = Float.NEGATIVE_INFINITY;
        for (int index = axis; index < positions.length; index += 3) {
            result = Math.max(result, positions[index]);
        }
        return result;
    }

    private static void assertNondegenerate(float[] positions) {
        for (int triangle = 0; triangle < positions.length / 9; triangle++) {
            int offset = triangle * 9;
            float edgeOneX = positions[offset + 3] - positions[offset];
            float edgeOneY = positions[offset + 4] - positions[offset + 1];
            float edgeOneZ = positions[offset + 5] - positions[offset + 2];
            float edgeTwoX = positions[offset + 6] - positions[offset];
            float edgeTwoY = positions[offset + 7] - positions[offset + 1];
            float edgeTwoZ = positions[offset + 8] - positions[offset + 2];
            float crossX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
            float crossY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
            float crossZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
            assertTrue(
                    crossX * crossX + crossY * crossY + crossZ * crossZ > 0.0F);
        }
    }
}
