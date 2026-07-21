package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

final class EmissionLightContractTest {
    @Test
    void minecraftLightLevelUsesSquaredRadiusCalibration() {
        assertEquals(0.0F, CpuSectionLights.emissionScale(0));
        assertEquals(1.0F, CpuSectionLights.emissionScale(3), 1.0E-6F);
        assertEquals(49.0F / 9.0F, CpuSectionLights.emissionScale(7), 1.0E-6F);
        assertEquals(25.0F, CpuSectionLights.emissionScale(15));
        assertEquals(25.0F, CpuSectionLights.emissionScale(100));
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
    void treePacksConsecutiveSiblingsAndSeparateForwardReverseMetadata() {
        List<CpuLightTree.Leaf> leaves = List.of(
                leaf(0.0F, 1.0F, 0),
                leaf(4.0F, 2.0F, 1),
                leaf(8.0F, 3.0F, 2));
        CpuLightTree.Result tree = CpuLightTree.build(
                leaves, leaves.size(), CpuLightTree.LOCAL_SOFTENING_SCALE);
        assertEquals(5, tree.nodeCount());
        assertEquals(6.0F, tree.power(), 1.0E-6F);
        int[] bounds = tree.packNodeBounds();
        int[] forward = tree.packNodeForward();
        int[] reverse = tree.packNodeReverse();
        int[] combined = new int[bounds.length + forward.length + reverse.length];
        tree.packInto(combined, 0, bounds.length, bounds.length + forward.length);
        assertArrayEquals(
                bounds,
                java.util.Arrays.copyOfRange(combined, 0, bounds.length));
        assertArrayEquals(
                forward,
                java.util.Arrays.copyOfRange(
                        combined, bounds.length, bounds.length + forward.length));
        assertArrayEquals(
                reverse,
                java.util.Arrays.copyOfRange(
                        combined, bounds.length + forward.length, combined.length));
        assertEquals(tree.nodeCount() * 8, bounds.length);
        assertEquals(tree.nodeCount(), forward.length);
        assertEquals(tree.nodeCount(), reverse.length);
        assertEquals(CpuLightTree.NO_INDEX, reverse[0]);
        for (int node = 0; node < tree.nodeCount(); node++) {
            int childOrLeaf = forward[node];
            if ((childOrLeaf & CpuLightTree.LEAF_FLAG) == 0) {
                int left = childOrLeaf;
                int right = left + 1;
                assertEquals(1, left & 1);
                assertEquals(0, right & 1);
                assertEquals(node, reverse[left]);
                assertEquals(node, reverse[right]);
            }
        }
        for (int leaf = 0; leaf < leaves.size(); leaf++) {
            int node = tree.leafNode(leaf);
            assertNotEquals(0, forward[node] & CpuLightTree.LEAF_FLAG);
            assertEquals(leaf, forward[node] & CpuLightTree.INDEX_MASK);
            assertNotEquals(CpuLightTree.NO_INDEX, reverse[node]);
        }
    }

    @Test
    void worldTreePacksBoundsForwardAndReverseStreamsInAddressOrder() {
        int[] bounds = new int[16];
        int[] forward = {3, CpuLightTree.LEAF_FLAG};
        int[] reverse = {CpuLightTree.NO_INDEX, 0};
        CpuWorldLightTree.Result tree = new CpuWorldLightTree.Result(
                bounds, forward, reverse, new int[] {1});
        int[] packed = tree.pack();

        assertEquals(2, tree.nodeCount());
        assertEquals(64L, tree.forwardByteOffset());
        assertEquals(72L, tree.reverseByteOffset());
        assertArrayEquals(bounds, java.util.Arrays.copyOfRange(packed, 0, 16));
        assertArrayEquals(forward, java.util.Arrays.copyOfRange(packed, 16, 18));
        assertArrayEquals(reverse, java.util.Arrays.copyOfRange(packed, 18, 20));
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
    void worldTreeRefitKeepsExactForwardAndReverseLeafMapping() {
        ArrayList<GpuCluster> clusters = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            clusters.add(cluster(index, index + 1.0F));
        }
        CpuWorldLightTree tree = new CpuWorldLightTree();
        CpuWorldLightTree.Result initial = tree.update(clusters, 0, 0, 0);
        assertWorldLeafMapping(initial, clusters.size());
        assertEquals(36.0F, Float.intBitsToFloat(initial.pack()[3]), 1.0E-6F);

        clusters.remove(0);
        Collections.reverse(clusters);
        CpuWorldLightTree.Result refitted = tree.update(clusters, 16, 0, 0);
        assertWorldLeafMapping(refitted, clusters.size());
        assertEquals(35.0F, Float.intBitsToFloat(refitted.pack()[3]), 1.0E-6F);
        boolean hasInactiveLeaf = false;
        for (int node = 0; node < refitted.nodeCount(); node++) {
            if (Float.intBitsToFloat(refitted.pack()[node * 8 + 3]) == 0.0F) {
                hasInactiveLeaf = true;
                break;
            }
        }
        assertTrue(hasInactiveLeaf);
    }

    @Test
    void worldWithoutEmittersMapsEveryResidentClusterToNoLight() {
        CpuWorldLightTree tree = new CpuWorldLightTree();
        List<GpuCluster> clusters = List.of(
                emptyCluster(1),
                emptyCluster(2),
                emptyCluster(3));

        CpuWorldLightTree.Result result = tree.update(clusters, 0, 0, 0);

        assertTrue(result.isEmpty());
        for (int clusterIndex = 0; clusterIndex < clusters.size(); clusterIndex++) {
            assertEquals(CpuLightTree.NO_INDEX, result.leafNode(clusterIndex));
        }
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> result.leafNode(clusters.size()));
    }

    private static void assertWorldLeafMapping(CpuWorldLightTree.Result tree, int clusterCount) {
        int forwardOffset = Math.toIntExact(tree.forwardByteOffset() / Integer.BYTES);
        for (int clusterIndex = 0; clusterIndex < clusterCount; clusterIndex++) {
            int leafNode = tree.leafNode(clusterIndex);
            assertNotEquals(CpuLightTree.NO_INDEX, leafNode);
            assertEquals(
                    CpuLightTree.LEAF_FLAG | clusterIndex,
                    tree.pack()[forwardOffset + leafNode]);
        }
    }

    private static GpuCluster cluster(int index, float power) {
        CpuLightTree.Bounds bounds = new CpuLightTree.Bounds(
                0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        return new GpuCluster(
                index,
                index,
                0,
                0,
                null,
                null,
                new CpuSectionLights.Summary(1, bounds, power));
    }

    private static GpuCluster emptyCluster(int index) {
        return new GpuCluster(
                index,
                index,
                0,
                0,
                null,
                null,
                CpuSectionLights.EMPTY.summary());
    }

    private static CpuLightTree.Leaf leaf(float x, float power, int index) {
        CpuLightTree.Bounds bounds = new CpuLightTree.Bounds(x, 0.0F, 0.0F, x + 1.0F, 1.0F, 1.0F);
        return new CpuLightTree.Leaf(bounds, x + 0.5F, 0.5F, 0.5F, power, index);
    }
}
