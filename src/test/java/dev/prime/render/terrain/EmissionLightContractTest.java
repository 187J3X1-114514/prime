package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void uniformFallbackKeepsEveryCellInTheSamplingSupport() {
        EmissionDistribution distribution = EmissionDistribution.uniform();
        float total = 0.0F;
        for (int index = 0; index < EmissionDistribution.CELL_COUNT; index++) {
            assertTrue(distribution.aliasProbability(index) > 0.0F);
            assertTrue(distribution.probabilityMass(index) > 0.0F);
            total += distribution.probabilityMass(index);
        }
        assertEquals(1.0F, total, 1.0E-5F);
    }

    @Test
    void treeStoresForwardChildrenAndReverseParentSiblingLinks() {
        List<CpuLightTree.Leaf> leaves = List.of(
                leaf(0.0F, 1.0F, 0),
                leaf(4.0F, 2.0F, 1),
                leaf(8.0F, 3.0F, 2));
        CpuLightTree.Result tree = CpuLightTree.build(
                leaves, leaves.size(), CpuLightTree.SECTION_SOFTENING_SCALE);
        assertEquals(5, tree.nodeCount());
        assertEquals(6.0F, tree.power(), 1.0E-6F);
        int[] words = tree.packNodes();
        for (int leaf = 0; leaf < leaves.size(); leaf++) {
            int node = tree.leafNode(leaf);
            int word = node * 12;
            assertEquals(leaf, words[word + 8]);
            assertEquals(CpuLightTree.NO_INDEX, words[word + 9]);
            assertNotEquals(CpuLightTree.NO_INDEX, words[word + 10]);
            assertNotEquals(CpuLightTree.NO_INDEX, words[word + 11]);
        }
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
        assertTrue(integrator.contains("primeSampleAreaLight"));
        assertTrue(integrator.contains("primeEvaluateAreaLight"));
    }

    private static CpuLightTree.Leaf leaf(float x, float power, int index) {
        CpuLightTree.Bounds bounds = new CpuLightTree.Bounds(x, 0.0F, 0.0F, x + 1.0F, 1.0F, 1.0F);
        return new CpuLightTree.Leaf(bounds, x + 0.5F, 0.5F, 0.5F, power, index);
    }
}
