package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class EmissionLightContractTest {
    @Test
    void minecraftLightLevelUsesSquaredRadiusCalibration() {
        assertEquals(0.0F, CpuSectionLights.emissionScale(0));
        assertEquals(0.6F, CpuSectionLights.emissionScale(3), 1.0E-6F);
        assertEquals(49.0F / 15.0F, CpuSectionLights.emissionScale(7), 1.0E-6F);
        assertEquals(15.0F, CpuSectionLights.emissionScale(15));
        assertEquals(15.0F, CpuSectionLights.emissionScale(100));
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
                leaves, leaves.size(), CpuLightTree.SECTION_SOFTENING_SCALE);
        assertEquals(5, tree.nodeCount());
        assertEquals(6.0F, tree.power(), 1.0E-6F);
        int[] bounds = tree.packNodeBounds();
        int[] forward = tree.packNodeForward();
        int[] reverse = tree.packNodeReverse();
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
    void shadersUseTheSameForwardAndReverseAreaLightDistribution() throws IOException {
        Path shaderRoot = Path.of(System.getProperty("user.dir"), "shaders");
        String lights = Files.readString(shaderRoot.resolve("lights.glsl"));
        String integrator = Files.readString(shaderRoot.resolve("integrator.glsl"));
        assertTrue(lights.contains("primePickLightTree"));
        assertTrue(lights.contains("primeLightTreeSelectionPdf"));
        assertTrue(lights.contains("selectedCell.probabilityMass / cellArea"));
        assertTrue(lights.contains("cell.probabilityMass / cellArea"));
        assertTrue(lights.contains("primeLightCellVertices(selectedCell.geometry"));
        assertFalse(lights.contains("candidateRow"));
        String forwardTraversal = lights.substring(
                lights.indexOf("LightTreePick primePickLightTree"),
                lights.indexOf("float primeLightTreeSelectionPdf"));
        assertTrue(forwardTraversal.contains("LightNode node = nodes.nodes[root]"));
        assertTrue(forwardTraversal.contains(
                "uint childOrLeaf = forwardNodes.nodes[nodeIndex].childOrLeaf"));
        assertTrue(forwardTraversal.contains("uint rightIndex = leftIndex + 1u"));
        assertTrue(forwardTraversal.contains("node = left"));
        assertTrue(forwardTraversal.contains("node = right"));
        assertTrue(forwardTraversal.contains("float split = lowerBound + pdf * leftProbability"));
        assertFalse(forwardTraversal.contains("value /= leftProbability"));
        assertFalse(forwardTraversal.contains("/ rightProbability"));
        assertFalse(forwardTraversal.contains("nodes.nodes[nodeIndex]"));
        assertFalse(forwardTraversal.contains("reverseNodes"));
        String areaSampling = lights.substring(
                lights.indexOf("AreaLightSample primeSampleAreaLight"),
                lights.indexOf("vec3 primeResolveSampledAreaLightRadiance"));
        assertTrue(areaSampling.contains("float pdf = areaPdf * distanceSquared / lightCosine"));
        assertTrue(areaSampling.contains("if (!(lightCosine > 0.0))"));
        String areaEvaluation = lights.substring(
                lights.indexOf("LightEvaluation primeEvaluateAreaLight"),
                lights.indexOf("#endif"));
        assertEquals(1, areaEvaluation.split("primeEvaluateEmitterRadiance\\(", -1).length - 1);
        assertTrue(integrator.contains("primeSampleAreaLight"));
        assertTrue(integrator.contains("primeEvaluateAreaLight"));
    }

    @Test
    void cumulativeIntervalMatchesNormalizedInverseCdfTraversal() {
        float[] probabilities = {0.17F, 0.83F, 0.31F, 0.62F, 0.48F, 0.91F};
        float[] seeds = {0.01F, 0.13F, 0.29F, 0.47F, 0.68F, 0.88F, 0.99F};
        for (float seed : seeds) {
            float normalized = seed;
            float normalizedPdf = 1.0F;
            float lowerBound = 0.0F;
            float intervalPdf = 1.0F;
            for (float leftProbability : probabilities) {
                boolean normalizedLeft = normalized < leftProbability;
                float rightProbability = 1.0F - leftProbability;
                if (normalizedLeft) {
                    normalizedPdf *= leftProbability;
                    normalized /= leftProbability;
                } else {
                    normalizedPdf *= rightProbability;
                    normalized = (normalized - leftProbability) / rightProbability;
                }

                float split = lowerBound + intervalPdf * leftProbability;
                boolean intervalLeft = seed < split;
                assertEquals(normalizedLeft, intervalLeft);
                if (intervalLeft) {
                    intervalPdf *= leftProbability;
                } else {
                    intervalPdf *= rightProbability;
                    lowerBound = split;
                }
                assertEquals(normalizedPdf, intervalPdf, 1.0E-7F);
            }
        }
    }

    private static CpuLightTree.Leaf leaf(float x, float power, int index) {
        CpuLightTree.Bounds bounds = new CpuLightTree.Bounds(x, 0.0F, 0.0F, x + 1.0F, 1.0F, 1.0F);
        return new CpuLightTree.Leaf(bounds, x + 0.5F, 0.5F, 0.5F, power, index);
    }
}
