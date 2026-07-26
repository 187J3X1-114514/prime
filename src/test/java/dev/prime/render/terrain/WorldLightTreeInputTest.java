package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        ArrayList<GpuCluster> clusters = new ArrayList<>();
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
                    expected.leafNode(index),
                    actual.leafNode(index));
        }
    }

    @Test
    void buildDependsOnlyOnCurrentInput() {
        ArrayList<GpuCluster> initial = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            initial.add(cluster(index, index + 1.0F));
        }
        ArrayList<GpuCluster> changed = new ArrayList<>(
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
                    expected.leafNode(index),
                    actual.leafNode(index));
        }
    }

    private static GpuCluster cluster(int index, float power) {
        return new GpuCluster(
                index,
                index,
                0,
                0,
                null,
                null,
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
}
