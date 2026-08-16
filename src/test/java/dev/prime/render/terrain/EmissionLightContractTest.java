package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class EmissionLightContractTest {
    @Test
    void minecraftLightLevelUsesSquaredRadiusCalibration() {
        assertEquals(0.0F, CpuSectionLights.emissionScale(0));
        assertEquals(3.0F / 50.0F, CpuSectionLights.emissionScale(3), 1.0E-6F);
        assertEquals(49.0F / 150.0F, CpuSectionLights.emissionScale(7), 1.0E-6F);
        assertEquals(1.5F, CpuSectionLights.emissionScale(15));
        assertEquals(1.5F, CpuSectionLights.emissionScale(100));
    }

    @Test
    void triangularSubdivisionHasExactlyEqualAreaCells() {
        boolean[][][] seen = new boolean[EmissionDistribution.SUBDIVISION]
                [EmissionDistribution.SUBDIVISION][2];
        float expectedTwiceArea = 1.0F
                / (EmissionDistribution.SUBDIVISION * EmissionDistribution.SUBDIVISION);
        for (int index = 0; index < EmissionDistribution.CELL_COUNT; index++) {
            EmissionDistribution.Cell cell = EmissionDistribution.cell(index);
            assertTrue(cell.column() + cell.row() < EmissionDistribution.SUBDIVISION);
            assertTrue(!cell.upper()
                    || cell.column() + cell.row() < EmissionDistribution.SUBDIVISION - 1);
            assertTrue(!seen[cell.column()][cell.row()][cell.upper() ? 1 : 0]);
            seen[cell.column()][cell.row()][cell.upper() ? 1 : 0] = true;
            float[][] vertices = cell.vertices();
            for (float[] vertex : vertices) {
                assertEquals(1.0F, vertex[0] + vertex[1] + vertex[2], 1.0E-6F);
                assertTrue(vertex[0] >= -1.0E-6F && vertex[1] >= -1.0E-6F && vertex[2] >= -1.0E-6F);
            }
            float twiceArea = Math.abs(
                    (vertices[1][1] - vertices[0][1]) * (vertices[2][2] - vertices[0][2])
                            - (vertices[1][2] - vertices[0][2]) * (vertices[2][1] - vertices[0][1]));
            assertEquals(expectedTwiceArea, twiceArea, 1.0E-6F);
        }
    }

    @Test
    void packedCellGeometryRoundTripsWithoutShaderSearch() {
        for (int index = 0; index < EmissionDistribution.CELL_COUNT; index++) {
            EmissionDistribution.Cell cell = EmissionDistribution.cell(index);
            int geometry = cell.packedGeometry();
            assertEquals(cell.column(), geometry & 0xf);
            assertEquals(cell.row(), geometry >>> 4 & 0xf);
            assertEquals(cell.upper(), (geometry & 0x100) != 0);
            assertEquals(0, geometry & ~0x1ff);
        }
    }

    @Test
    void aliasAndCellGeometryShareOneWordWithoutLosingEitherIndex() {
        for (int cellIndex = 0; cellIndex < EmissionDistribution.CELL_COUNT; cellIndex++) {
            int alias = EmissionDistribution.CELL_COUNT - 1 - cellIndex;
            int packed = EmissionDistribution.packAliasGeometry(alias, cellIndex);
            assertEquals(alias, packed & 0xff);
            assertEquals(
                    EmissionDistribution.cell(cellIndex).packedGeometry(),
                    packed >>> 8);
        }
    }

    @Test
    void emissionImportanceUsesTheLargestLinearRec2020Component() {
        assertEquals(1.0F, EmissionDistribution.linearSrgbToRec2020Maximum(1.0F, 1.0F, 1.0F), 1.0E-6F);
        assertEquals(0.6274039F, EmissionDistribution.linearSrgbToRec2020Maximum(1.0F, 0.0F, 0.0F), 1.0E-6F);
        assertEquals(0.9195404F, EmissionDistribution.linearSrgbToRec2020Maximum(0.0F, 1.0F, 0.0F), 1.0E-6F);
        assertEquals(0.8955953F, EmissionDistribution.linearSrgbToRec2020Maximum(0.0F, 0.0F, 1.0F), 1.0E-6F);
    }

    @Test
    void uniformFallbackKeepsEveryCellInTheSamplingSupport() {
        EmissionDistribution distribution = EmissionDistribution.uniform();
        float total = 0.0F;
        for (int index = 0; index < EmissionDistribution.CELL_COUNT; index++) {
            assertTrue(distribution.aliasProbability(index) > 0.0F);
            assertTrue(distribution.probabilityMass(index) > 0.0F);
            total += distribution.probabilityMass(index);
        }
        assertEquals(1.0F, total, 1.0E-5F);
        assertEquals(1.0F, distribution.meanImportance(), 1.0E-6F);
    }

    @Test
    void uniformTriangleDistributionHasExactPositionMoments() {
        EmissionDistribution.SpatialMoments moments =
                EmissionDistribution.uniform().spatialMoments();

        assertEquals(1.0F / 3.0F, moments.meanU(), 1.0E-6F);
        assertEquals(1.0F / 3.0F, moments.meanV(), 1.0E-6F);
        assertEquals(1.0F / 6.0F, moments.meanSquareU(), 1.0E-6F);
        assertEquals(1.0F / 12.0F, moments.meanProductUv(), 1.0E-6F);
        assertEquals(1.0F / 6.0F, moments.meanSquareV(), 1.0E-6F);
        assertEquals(
                1.0F / 9.0F,
                moments.positionVariance(
                        1.0F, 0.0F, 0.0F,
                        0.0F, 1.0F, 0.0F),
                1.0E-6F);
    }

    @Test
    void sectionRetainsMoreThan1024DistinctImportanceDistributions() {
        int count = 1026;
        CpuSectionLights.Builder builder = new CpuSectionLights.Builder();
        for (int index = 0; index < count; index++) {
            builder.addTriangle(
                    index,
                    0.0F,
                    0.0F,
                    index + 1.0F,
                    0.0F,
                    0.0F,
                    index,
                    1.0F,
                    0.0F,
                    0.0F,
                    0.0F,
                    1.0F,
                    0,
                    0,
                    0,
                    0xff000000 | index,
                    0,
                    false,
                    15,
                    null,
                    null);
        }

        CpuSectionLights lights = builder.build();
        int[] packed = lights.pack(0L);
        int emitterStart = packed[6] / Integer.BYTES;
        int lastEmitter = emitterStart
                + (count - 1) * (ShaderAbi.LIGHT_EMITTER_SIZE / Integer.BYTES);

        assertEquals(count, lights.emitterCount());
        assertEquals(
                (count - 1) * EmissionDistribution.CELL_COUNT,
                packed[lastEmitter + 20]);
    }

    @Test
    void emitterNormalFollowsTheAuthoredOutwardHemisphere() {
        CpuSectionLights.Builder builder = new CpuSectionLights.Builder();
        builder.addTriangle(
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                0.0F,
                1.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                0,
                0,
                0,
                -1,
                0,
                false,
                15,
                null,
                null);

        CpuSectionLights lights = builder.build();
        int[] packed = lights.pack(0L);
        int emitterStart = packed[6] / Integer.BYTES;

        assertEquals(0.0F, Float.intBitsToFloat(packed[emitterStart + 12]), 0.0F);
        assertEquals(0.0F, Float.intBitsToFloat(packed[emitterStart + 13]), 0.0F);
        assertEquals(1.0F, Float.intBitsToFloat(packed[emitterStart + 14]), 0.0F);
    }

    @Test
    void treePacksConsecutiveSiblingsClusteredLeavesAndBitTrails() {
        List<CpuLightTree.Leaf> leaves = List.of(
                leaf(0.0F, 1.0F, 0),
                leaf(4.0F, 2.0F, 1),
                leaf(8.0F, 3.0F, 2));
        CpuLightTree.Result tree = CpuLightTree.build(
                leaves, leaves.size(), CpuLightTree.LOCAL_SOFTENING_SCALE);
        assertEquals(6.0F, tree.power(), 1.0E-6F);
        int[] nodes = tree.packNodes();
        int[] leafDescriptors = tree.packLeaves();
        int[] entries = tree.packEntries();
        int[] combined = new int[nodes.length + leafDescriptors.length + entries.length];
        tree.packInto(combined, 0, nodes.length, nodes.length + leafDescriptors.length);
        assertArrayEquals(
                nodes,
                java.util.Arrays.copyOfRange(combined, 0, nodes.length));
        assertArrayEquals(
                leafDescriptors,
                java.util.Arrays.copyOfRange(
                        combined, nodes.length, nodes.length + leafDescriptors.length));
        assertArrayEquals(
                entries,
                java.util.Arrays.copyOfRange(
                        combined, nodes.length + leafDescriptors.length, combined.length));
        assertEquals(tree.nodeCount() * 8, nodes.length);
        assertEquals(tree.clusterCount() * 2, leafDescriptors.length);
        assertEquals(leaves.size() * 2, entries.length);
        for (int node = 0; node < tree.nodeCount(); node++) {
            int childOrLeaf = nodes[node * 8 + 6];
            if ((childOrLeaf & CpuLightTree.LEAF_FLAG) == 0) {
                int left = childOrLeaf;
                int right = left + 1;
                assertEquals(1, left & 1);
                assertEquals(0, right & 1);
            }
        }
        for (int leaf = 0; leaf < leaves.size(); leaf++) {
            int node = tree.leafNode(leaf);
            assertEquals(node, terminalNode(nodes, tree.leafPath(leaf)));
            int descriptor = nodes[node * 8 + 6];
            assertNotEquals(0, descriptor & CpuLightTree.LEAF_FLAG);
            assertLeafContains(leafDescriptors, entries, descriptor & CpuLightTree.INDEX_MASK, leaf);
        }
    }

    @Test
    void treeKeepsPathologicalSahSplitsWithinPackedDepth() {
        ArrayList<CpuLightTree.Leaf> leaves = new ArrayList<>();
        float x = 1.0F;
        float power = 1.0F;
        for (int index = 0; index < 32; index++) {
            leaves.add(leaf(x, power, index));
            x *= 1.4F;
            power *= 4.0F;
        }

        CpuLightTree.Result tree = CpuLightTree.build(
                leaves, leaves.size(), CpuLightTree.LOCAL_SOFTENING_SCALE);
        int[] nodes = tree.packNodes();
        int[] leafDescriptors = tree.packLeaves();
        int[] entries = tree.packEntries();

        for (int index = 0; index < leaves.size(); index++) {
            int path = tree.leafPath(index);
            assertTrue((path >>> CpuLightTree.MAX_PATH_DEPTH) <= CpuLightTree.MAX_PATH_DEPTH);
            int node = terminalNode(nodes, path);
            assertEquals(tree.leafNode(index), node);
            int descriptor = nodes[node * 8 + 6];
            assertNotEquals(0, descriptor & CpuLightTree.LEAF_FLAG);
            assertLeafContains(
                    leafDescriptors,
                    entries,
                    descriptor & CpuLightTree.INDEX_MASK,
                    index);
        }
    }

    @Test
    void treeMomentsTrackPowerWeightedCentroidAndSpatialVariance() {
        CpuLightTree.Leaves leaves = new CpuLightTree.Leaves(2);
        leaves.addWithSpatialVariance(
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                1.0F,
                0,
                LightDirection.full());
        leaves.addWithSpatialVariance(
                10.0F,
                0.0F,
                0.0F,
                10.0F,
                0.0F,
                0.0F,
                10.0F,
                0.0F,
                0.0F,
                3.0F,
                3.0F,
                1,
                LightDirection.full());

        CpuLightTree.Result tree = CpuLightTree.buildOwned(
                leaves, 2, CpuLightTree.LOCAL_SOFTENING_SCALE);
        int[] nodes = tree.packNodes();
        float rootSoftening = Float.intBitsToFloat(nodes[5]);
        float minimumX = Float.float16ToFloat((short) nodes[0]);
        float maximumX = Float.float16ToFloat((short) (nodes[1] >>> 16));
        float normalizedCenterX = (nodes[7] & 0x3ff) / 1023.0F;
        float centerX = minimumX + (maximumX - minimumX) * normalizedCenterX;

        assertEquals(21.25F * CpuLightTree.LOCAL_SOFTENING_SCALE, rootSoftening, 1.0E-6F);
        assertEquals(7.5F, centerX, (maximumX - minimumX) / 1023.0F);
    }

    @Test
    void worldTreePacksNodesLeavesAndEntriesInAddressOrder() {
        int[] nodes = new int[8];
        int[] leaves = {0, 1};
        int[] entries = {1, Float.floatToRawIntBits(2.0F)};
        CpuWorldLightTree.Result tree = new CpuWorldLightTree.Result(
                nodes, leaves, entries, new int[] {0});
        int[] packed = tree.pack();

        assertEquals(1, tree.nodeCount());
        assertEquals(32L, tree.leafByteOffset());
        assertEquals(40L, tree.entryByteOffset());
        assertArrayEquals(nodes, java.util.Arrays.copyOfRange(packed, 0, 8));
        assertArrayEquals(leaves, java.util.Arrays.copyOfRange(packed, 8, 10));
        assertArrayEquals(entries, java.util.Arrays.copyOfRange(packed, 10, 12));
    }

    @Test
    void compactionOnlyUpdateReusesExistingWorldLightUpload() {
        CpuWorldLightTree.Result existingTree = new CpuWorldLightTree.Result(
                new int[8],
                new int[] {0, 1},
                new int[] {0, Float.floatToRawIntBits(1.0F)},
                new int[] {0});
        CpuWorldLightTree.Result emptyTree =
                new CpuWorldLightTree.Result(new int[0], new int[0], new int[0], new int[0]);

        assertFalse(TerrainScene.requiresWorldLightUpload(false, existingTree));
        assertTrue(TerrainScene.requiresWorldLightUpload(true, existingTree));
        assertFalse(TerrainScene.requiresWorldLightUpload(true, emptyTree));
    }

    @Test
    void treeRejectsAggregatePowerOutsideThePackedF32Domain() {
        List<CpuLightTree.Leaf> leaves = List.of(
                leaf(0.0F, Float.MAX_VALUE, 0),
                leaf(1.0F, Float.MAX_VALUE, 1));

        assertThrows(
                IllegalArgumentException.class,
                () -> CpuLightTree.build(
                        leaves, leaves.size(), CpuLightTree.LOCAL_SOFTENING_SCALE));
    }

    @Test
    void worldTreeRebuildKeepsExactBitTrailLeafMapping() {
        ArrayList<WorldLightTreeInput.Entry> clusters = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            clusters.add(cluster(index, index + 1.0F));
        }
        CpuWorldLightTree.Result initial =
                CpuWorldLightTree.build(worldLightInput(clusters, 0, 0, 0));
        assertWorldLeafMapping(initial, clusters.size());
        assertTrue(initial.nodeCount() <= 2 * clusters.size() - 1);
        assertEquals(36.0F, Float.intBitsToFloat(initial.pack()[4]), 1.0E-6F);

        clusters.remove(0);
        CpuWorldLightTree.Result rebuilt =
                CpuWorldLightTree.build(worldLightInput(clusters, 16, 0, 0));
        assertWorldLeafMapping(rebuilt, clusters.size());
        assertTrue(rebuilt.nodeCount() <= 2 * clusters.size() - 1);
        assertEquals(35.0F, Float.intBitsToFloat(rebuilt.pack()[4]), 1.0E-6F);
        for (int node = 0; node < rebuilt.nodeCount(); node++) {
            assertTrue(Float.intBitsToFloat(rebuilt.pack()[node * 8 + 4]) > 0.0F);
        }
    }

    @Test
    void worldWithoutEmittersMapsEveryResidentClusterToNoLight() {
        List<WorldLightTreeInput.Entry> clusters = List.of(
                emptyCluster(1),
                emptyCluster(2),
                emptyCluster(3));

        CpuWorldLightTree.Result result =
                CpuWorldLightTree.build(worldLightInput(clusters, 0, 0, 0));

        assertTrue(result.isEmpty());
        for (int clusterIndex = 0; clusterIndex < clusters.size(); clusterIndex++) {
            assertEquals(CpuLightTree.NO_INDEX, result.lightPath(clusterIndex));
        }
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> result.lightPath(clusters.size()));
    }

    private static void assertWorldLeafMapping(CpuWorldLightTree.Result tree, int clusterCount) {
        int[] packed = tree.pack();
        int leafOffset = Math.toIntExact(tree.leafByteOffset() / Integer.BYTES);
        int entryOffset = Math.toIntExact(tree.entryByteOffset() / Integer.BYTES);
        for (int clusterIndex = 0; clusterIndex < clusterCount; clusterIndex++) {
            int path = tree.lightPath(clusterIndex);
            assertNotEquals(CpuLightTree.NO_INDEX, path);
            int node = terminalNode(packed, path);
            int descriptor = packed[node * 8 + 6];
            assertNotEquals(0, descriptor & CpuLightTree.LEAF_FLAG);
            int leaf = descriptor & CpuLightTree.INDEX_MASK;
            int first = packed[leafOffset + leaf * 2];
            int count = packed[leafOffset + leaf * 2 + 1];
            boolean found = false;
            for (int slot = 0; slot < count; slot++) {
                found |= packed[entryOffset + (first + slot) * 2] == clusterIndex;
            }
            assertTrue(found);
        }
    }

    private static int terminalNode(int[] nodes, int packedPath) {
        int depth = packedPath >>> CpuLightTree.MAX_PATH_DEPTH;
        int node = 0;
        for (int level = 0; level < depth; level++) {
            int firstChild = nodes[node * 8 + 6];
            assertEquals(0, firstChild & CpuLightTree.LEAF_FLAG);
            node = firstChild + (packedPath >>> level & 1);
        }
        return node;
    }

    private static void assertLeafContains(
            int[] leaves, int[] entries, int leaf, int expectedIndex) {
        int first = leaves[leaf * 2];
        int count = leaves[leaf * 2 + 1];
        assertTrue(count > 0 && count <= CpuLightTree.MAX_LIGHTS_PER_LEAF);
        boolean found = false;
        for (int slot = 0; slot < count; slot++) {
            found |= entries[(first + slot) * 2] == expectedIndex;
        }
        assertTrue(found);
    }

    private static WorldLightTreeInput.Entry cluster(int index, float power) {
        CpuLightTree.Bounds bounds = new CpuLightTree.Bounds(
                0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        return new WorldLightTreeInput.Entry(
                index,
                index,
                0,
                0,
                new CompiledClusterLights.Summary(
                        1,
                        bounds.minX(),
                        bounds.minY(),
                        bounds.minZ(),
                        bounds.maxX(),
                        bounds.maxY(),
                        bounds.maxZ(),
                        power));
    }

    private static WorldLightTreeInput worldLightInput(
            List<WorldLightTreeInput.Entry> clusters,
            int originX,
            int originY,
            int originZ) {
        return WorldLightTreeInput.capture(
                clusters, originX, originY, originZ);
    }

    private static WorldLightTreeInput.Entry emptyCluster(int index) {
        return new WorldLightTreeInput.Entry(
                index,
                index,
                0,
                0,
                CompiledClusterLights.EMPTY.summary());
    }

    private static CpuLightTree.Leaf leaf(float x, float power, int index) {
        CpuLightTree.Bounds bounds = new CpuLightTree.Bounds(x, 0.0F, 0.0F, x + 1.0F, 1.0F, 1.0F);
        return new CpuLightTree.Leaf(bounds, x + 0.5F, 0.5F, 0.5F, power, index);
    }
}
