package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.shader.ShaderAbi;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorldLightTreeInputTest {
    @Test
    void captureRejectsDuplicateOrUnstableClusterOrder() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldLightTreeInput.capture(
                        List.of(cluster(2, 1.0F), cluster(1, 1.0F)),
                        0,
                        0,
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldLightTreeInput.capture(
                        List.of(cluster(1, 1.0F), cluster(1, 2.0F)),
                        0,
                        0,
                        0));
    }

    @Test
    void identicalInputBuildsExactTopology() {
        ArrayList<WorldLightTreeInput.Entry> clusters = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            clusters.add(cluster(index, index + 1.0F));
        }
        WorldLightTreeInput input =
                WorldLightTreeInput.capture(clusters, 16, 0, 0);
        CpuWorldLightTree.Result expected = CpuWorldLightTree.build(input);
        CpuWorldLightTree.Result actual = CpuWorldLightTree.build(input);

        assertArrayEquals(expected.pack(), actual.pack());
        assertEquals(expected.nodeCount(), actual.nodeCount());
        for (int index = 0; index < clusters.size(); index++) {
            assertEquals(
                    expected.lightPath(index),
                    actual.lightPath(index));
        }
    }

    @Test
    void buildDependsOnlyOnCurrentInput() {
        ArrayList<WorldLightTreeInput.Entry> initial = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            initial.add(cluster(index, index + 1.0F));
        }
        ArrayList<WorldLightTreeInput.Entry> changed = new ArrayList<>(
                initial.subList(1, initial.size()));
        changed.add(cluster(9, 3.0F));
        WorldLightTreeInput changedInput =
                WorldLightTreeInput.capture(changed, 16, 0, 0);
        CpuWorldLightTree.Result expected = CpuWorldLightTree.build(changedInput);

        CpuWorldLightTree.build(
                WorldLightTreeInput.capture(initial, 0, 0, 0));
        CpuWorldLightTree.Result actual = CpuWorldLightTree.build(changedInput);

        assertArrayEquals(expected.pack(), actual.pack());
        for (int index = 0; index < changed.size(); index++) {
            assertEquals(
                    expected.lightPath(index),
                    actual.lightPath(index));
        }
    }

    @Test
    void clusterWithoutEmittersHasNoWorldLightTreeLeaf() {
        WorldLightTreeInput.Entry staticCluster = cluster(1, 2.0F);
        WorldLightTreeInput.Entry unlitCluster = new WorldLightTreeInput.Entry(
                2,
                0,
                0,
                0,
                new CompiledClusterLights.Summary(
                        0,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F));

        CpuWorldLightTree.Result tree = CpuWorldLightTree.build(
                WorldLightTreeInput.capture(
                        List.of(staticCluster, unlitCluster), 0, 0, 0));

        assertNotEquals(CpuLightTree.NO_INDEX, tree.lightPath(0));
        assertEquals(CpuLightTree.NO_INDEX, tree.lightPath(1));
    }

    @Test
    void flatProposalPacksActiveSectionsAndNormalizedMassesContiguously() {
        CpuWorldLightTree.Result tree = CpuWorldLightTree.build(
                WorldLightTreeInput.capture(
                        List.of(cluster(1, 1.0F), emptyCluster(2), cluster(3, 3.0F)),
                        0,
                        0,
                        0));
        int[] words = tree.pack();
        int summaryWord = Math.toIntExact(tree.summaryByteOffset() / Integer.BYTES);
        int summaryStride = ShaderAbi.WORLD_LIGHT_SUMMARY_SIZE / Integer.BYTES;
        int aliasWord = Math.toIntExact(tree.aliasByteOffset() / Integer.BYTES);

        assertEquals(2, tree.summaryCount());
        assertEquals(0, words[summaryWord + 9]);
        assertEquals(2, words[summaryWord + summaryStride + 9]);
        assertEquals(0.25F, Float.intBitsToFloat(words[summaryWord + 7]), 1.0E-6F);
        assertEquals(
                0.75F,
                Float.intBitsToFloat(words[summaryWord + summaryStride + 7]),
                1.0E-6F);
        for (int index = 0; index < tree.summaryCount(); index++) {
            float threshold = Float.intBitsToFloat(words[aliasWord + index * 2]);
            int alias = words[aliasWord + index * 2 + 1];
            assertTrue(threshold >= 0.0F && threshold <= 1.0F);
            assertTrue(alias >= 0 && alias < tree.summaryCount());
        }
        assertEquals(tree.summaryByteOffset(), getLong(words, 6));
        assertEquals(tree.aliasByteOffset(), getLong(words, 8));
    }

    private static WorldLightTreeInput.Entry cluster(int index, float power) {
        return new WorldLightTreeInput.Entry(
                index,
                index,
                0,
                0,
                new CompiledClusterLights.Summary(
                        1,
                        0.0F,
                        0.0F,
                        0.0F,
                        1.0F,
                        1.0F,
                        1.0F,
                        power));
    }

    private static WorldLightTreeInput.Entry emptyCluster(int index) {
        return new WorldLightTreeInput.Entry(
                index,
                index,
                0,
                0,
                new CompiledClusterLights.Summary(
                        0,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F));
    }

    private static long getLong(int[] words, int offset) {
        return Integer.toUnsignedLong(words[offset]) | (long) words[offset + 1] << 32;
    }
}
