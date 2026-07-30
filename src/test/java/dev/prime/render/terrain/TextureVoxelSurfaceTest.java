package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void detailWindowUsesExactlyThreeClustersPerAxis() {
        int count = 0;
        for (int z = -8; z <= 8; z += SectionCluster.SECTION_SIZE) {
            for (int y = -8; y <= 8; y += SectionCluster.SECTION_SIZE) {
                for (int x = -8; x <= 8; x += SectionCluster.SECTION_SIZE) {
                    if (TerrainStreamer.clusterUsesVoxelSurfaces(
                            x, y, z, 0, 0, 0)) {
                        count++;
                    }
                }
            }
        }
        assertEquals(27, count);
        assertTrue(TerrainStreamer.clusterUsesVoxelSurfaces(
                -4, -4, -4, 0, 0, 0));
        assertTrue(TerrainStreamer.clusterUsesVoxelSurfaces(
                4, 4, 4, 3, 3, 3));
        assertFalse(TerrainStreamer.clusterUsesVoxelSurfaces(
                8, 0, 0, 0, 0, 0));
        assertFalse(TerrainStreamer.clusterUsesVoxelSurfaces(
                -8, 0, 0, 0, 0, 0));
        assertFalse(TerrainStreamer.clusterUsesVoxelSurfaces(
                0, 8, 0, 0, 0, 0));
        assertTrue(TerrainStreamer.clusterUsesVoxelSurfaces(
                -8, 0, 0, -1, -1, -1));
        assertFalse(TerrainStreamer.clusterUsesVoxelSurfaces(
                4, 0, 0, -1, -1, -1));
    }

    @Test
    void crossingOneClusterInvalidatesOnlyTheEnteringAndLeavingSlabs() {
        int changed = 0;
        for (int z = -8; z <= 12; z += SectionCluster.SECTION_SIZE) {
            for (int y = -8; y <= 12; y += SectionCluster.SECTION_SIZE) {
                for (int x = -8; x <= 12; x += SectionCluster.SECTION_SIZE) {
                    if (TerrainStreamer.voxelSurfaceStateChanges(
                            x, y, z, 0, 0, 0, 4, 0, 0)) {
                        changed++;
                    }
                }
            }
        }

        assertEquals(18, changed);
        assertFalse(TerrainStreamer.voxelSurfaceStateChanges(
                0, 0, 0, 0, 0, 0, 4, 0, 0));
    }

    @Test
    void labPbrNormalAlphaProvidesRebasedAnimatedHeight() {
        LabPbrHeightMap height = LabPbrHeightMap.fromNormal(
                new int[] {
                    0xd600_0000, 0xfd00_0000,
                    0x8000_0000, 0xff00_0000
                },
                2,
                2,
                2,
                1,
                1,
                2);

        assertEquals(0.0F, height.sample(0, 0.25F, 0.5F));
        assertEquals(39.0F / 255.0F, height.sample(0, 0.75F, 0.5F));
        assertEquals(0.0F, height.sample(1, 0.25F, 0.5F));
        assertEquals(127.0F / 255.0F, height.sample(1, 0.75F, 0.5F));

        LabPbrHeightMap flat = LabPbrHeightMap.fromNormal(
                new int[] {0xff00_0000, 0xff00_0000},
                2,
                1,
                2,
                1,
                1,
                1);
        assertEquals(0.0F, flat.sample(0, 0.25F, 0.5F));
        assertEquals(0.0F, flat.sample(0, 0.75F, 0.5F));
    }

    @Test
    void borderReferenceUsesTheDarkestOuterPixel() {
        float[] heights = {
            0.4F, 0.5F, 0.6F,
            0.7F, 0.1F, 0.8F,
            0.9F, 0.3F, 1.0F
        };

        assertEquals(
                0.3F,
                TextureVoxelMeshBuilder.borderReferenceHeight(heights, 3));
        assertEquals(
                0.1F,
                TextureVoxelMeshBuilder.borderReferenceHeight(
                        new float[] {0.1F}, 1));

        TextureVoxelMeshBuilder.alignToReferencePlane(heights, 0.3F);
        assertArrayEquals(
                new float[] {
                    0.1F, 0.2F, 0.3F,
                    0.4F, 0.0F, 0.5F,
                    0.6F, 0.0F, 0.7F
                },
                heights,
                1.0E-7F);
    }

    @Test
    void voxelSurfaceStrengthKeepsOneThirtySecondAsTheDefault() {
        assertEquals(0.0F, VoxelSurfaceSettings.maximumHeight(0));
        assertEquals(
                1.0F / 32.0F,
                VoxelSurfaceSettings.maximumHeight(
                        VoxelSurfaceSettings.DEFAULT_STEPS));
        assertEquals(
                1.0F / 8.0F,
                VoxelSurfaceSettings.maximumHeight(
                        VoxelSurfaceSettings.MAXIMUM_STEPS));
    }

    @Test
    void generatedReliefUsesAbsoluteLumaStrength() {
        CpuVoxelMesh generated = TextureVoxelMeshBuilder.buildOpaqueHeightField(
                2,
                new int[] {
                    0xff80_8080, 0xff00_0000,
                    0xff00_0000, 0xff00_0000
                },
                2,
                1);

        assertEquals(
                TextureVoxelMeshBuilder.heightFromArgb(0xff80_8080),
                maximumAxis(generated.positions(), 2),
                1.0E-7F);
    }

    @Test
    void layeredCutoutUsesOpaqueReliefAndAStableOutwardOffset() {
        try (SectionMeshAccumulatorTest.TestSprite baseSprite =
                        new SectionMeshAccumulatorTest.TestSprite("layer_base");
                SectionMeshAccumulatorTest.TestSprite overlaySprite =
                        new SectionMeshAccumulatorTest.TestSprite("layer_overlay")) {
            int[] baseNormal = new int[16 * 16];
            int[] overlayNormal = new int[16 * 16];
            Arrays.fill(baseNormal, 0xe400_0000);
            Arrays.fill(overlayNormal, 0xcf00_0000);
            for (int index = 0; index < 16; index++) {
                baseNormal[index] = 0xff00_0000;
                overlayNormal[index] = 0xff00_0000;
            }
            LabPbrHeightMap baseHeight = LabPbrHeightMap.fromNormal(
                    baseNormal, 16, 16, 16, 16, 1, 1);
            LabPbrHeightMap overlayHeight = LabPbrHeightMap.fromNormal(
                    overlayNormal, 16, 16, 16, 16, 1, 1);
            LabPbrMaterialSet materials = new LabPbrMaterialSet(
                    Set.of(
                            baseSprite.contents().name(),
                            overlaySprite.contents().name()),
                    Set.of(),
                    Map.of(),
                    Map.of(
                            baseSprite.contents().name(), baseHeight,
                            overlaySprite.contents().name(), overlayHeight));
            SectionMeshAccumulator accumulator =
                    new SectionMeshAccumulator(materials, false);
            SectionMeshAccumulator.Quad face =
                    SectionMeshAccumulatorTest.horizontalQuad(
                            2.0F, 3.0F, 4.0F, 1.0F);
            accumulator.addQuad(
                    face, SectionMeshAccumulatorTest.opaqueSurface(baseSprite));
            accumulator.addQuad(
                    face, SectionMeshAccumulatorTest.cutoutSurface(overlaySprite));
            SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(
                    0,
                    0,
                    0,
                    TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES,
                    OpacityMicromapData.SUBDIVISION_LEVEL + 2,
                    true,
                    VoxelSurfaceSettings.BASE_HEIGHT);
            builder.add(0, 0, 0, accumulator.build());

            CpuClusterMesh cluster = builder.build();

            assertEquals(2, cluster.voxelMeshes().size());
            assertEquals(2, cluster.voxelInstances().count());
            assertArrayEquals(
                    cluster.voxelMeshes().get(0).positions(),
                    cluster.voxelMeshes().get(1).positions());
            assertEquals(
                    4.0F,
                    cluster.voxelInstances().translationZ(0),
                    1.0E-7F);
            assertEquals(
                    4.0F + TextureVoxelMeshBuilder.LAYER_OFFSET,
                    cluster.voxelInstances().translationZ(1),
                    1.0E-7F);
        }
    }

    @Test
    void flatGeneratedTextureAlignsItsOuterSurfaceToTheBlockPlane() {
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

        assertEquals(8, outward.triangleCount());
        assertEquals(0.0F, minimumAxis(outward.positions(), 2));
        assertEquals(0.0F, maximumAxis(outward.positions(), 2));
        assertEquals(0.0F, minimumAxis(inwardFacing.positions(), 2));
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
        CpuVoxelMesh voxelMesh = TextureVoxelMeshBuilder.buildOpaqueHeightField(
                1, new int[] {0xffff_ffff}, 2, 1);
        float[] positions = voxelMesh.positions();
        int[] primitives = voxelMesh.primitiveRecords();
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
