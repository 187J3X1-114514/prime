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
    void identicalSemanticUpdateSequenceReplaysExactTopology() {
        CpuWorldLightTree first = new CpuWorldLightTree();
        CpuWorldLightTree replay = new CpuWorldLightTree();
        ArrayList<GpuCluster> initial = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            initial.add(cluster(index, index + 1.0F));
        }
        WorldLightTreeInput initialInput =
                WorldLightTreeInput.capture(initial, 0, 0, 0);
        first.update(initialInput);
        replay.update(initialInput);

        ArrayList<GpuCluster> changed = new ArrayList<>(
                initial.subList(1, initial.size()));
        changed.add(cluster(9, 3.0F));
        WorldLightTreeInput changedInput =
                WorldLightTreeInput.capture(changed, 16, 0, 0);
        CpuWorldLightTree.Result expected = first.update(changedInput);
        CpuWorldLightTree.Result actual = replay.update(changedInput);

        assertArrayEquals(expected.pack(), actual.pack());
        assertEquals(expected.nodeCount(), actual.nodeCount());
        for (int index = 0; index < changed.size(); index++) {
            assertEquals(
                    expected.leafNode(index),
                    actual.leafNode(index));
        }
    }

    @Test
    void capturedIncrementalHistoryResumesTheExactNextTopology() {
        CpuWorldLightTree uninterrupted = new CpuWorldLightTree();
        ArrayList<GpuCluster> initial = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            initial.add(cluster(index, index + 1.0F));
        }
        uninterrupted.update(
                WorldLightTreeInput.capture(initial, 0, 0, 0));
        CpuWorldLightTree restored =
                new CpuWorldLightTree(uninterrupted.snapshot());

        ArrayList<GpuCluster> changed = new ArrayList<>(
                initial.subList(1, initial.size()));
        changed.add(cluster(9, 3.0F));
        WorldLightTreeInput changedInput =
                WorldLightTreeInput.capture(changed, 16, 0, 0);
        CpuWorldLightTree.Result expected =
                uninterrupted.update(changedInput);
        CpuWorldLightTree.Result actual =
                restored.update(changedInput);

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
