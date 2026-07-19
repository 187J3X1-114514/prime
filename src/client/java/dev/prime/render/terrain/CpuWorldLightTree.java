package dev.prime.render.terrain;

import dev.prime.render.shader.ShaderAbi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Rebuildable top level over the immutable virtual-cluster light trees. */
final class CpuWorldLightTree {
    private CpuWorldLightTree() {
    }

    static Result build(List<GpuCluster> clusters, int originX, int originY, int originZ) {
        ArrayList<CpuLightTree.Leaf> leaves = new ArrayList<>(clusters.size());
        for (int index = 0; index < clusters.size(); index++) {
            GpuCluster cluster = clusters.get(index);
            if (cluster.lights().isEmpty()) {
                continue;
            }
            float translateX = (cluster.clusterX() << 4) - originX;
            float translateY = (cluster.clusterY() << 4) - originY;
            float translateZ = (cluster.clusterZ() << 4) - originZ;
            CpuLightTree.Bounds bounds = cluster.lights().bounds().translated(
                    translateX, translateY, translateZ);
            leaves.add(new CpuLightTree.Leaf(
                    bounds,
                    (bounds.minX() + bounds.maxX()) * 0.5F,
                    (bounds.minY() + bounds.maxY()) * 0.5F,
                    (bounds.minZ() + bounds.maxZ()) * 0.5F,
                    cluster.lights().power(),
                    index));
        }
        if (leaves.isEmpty()) {
            int[] leafNodes = new int[clusters.size()];
            Arrays.fill(leafNodes, CpuLightTree.NO_INDEX);
            return new Result(new int[0], new int[0], new int[0], leafNodes);
        }
        CpuLightTree.Result tree = CpuLightTree.buildOwned(
                leaves, clusters.size(), CpuLightTree.WORLD_SOFTENING_SCALE);
        return Result.fromTree(tree, tree.leafNodes());
    }

    static final class Result {
        private final int[] packedWords;
        private final int nodeWordCount;
        private final int forwardWordCount;
        private final int[] leafNodes;

        Result(int[] nodeWords, int[] forwardWords, int[] reverseWords, int[] leafNodes) {
            int nodeWordsPerRecord = ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES;
            int forwardWordsPerRecord = ShaderAbi.LIGHT_NODE_FORWARD_SIZE / Integer.BYTES;
            int reverseWordsPerRecord = ShaderAbi.LIGHT_NODE_REVERSE_SIZE / Integer.BYTES;
            if (nodeWords.length % nodeWordsPerRecord != 0
                    || forwardWords.length % forwardWordsPerRecord != 0
                    || reverseWords.length % reverseWordsPerRecord != 0
                    || nodeWords.length / nodeWordsPerRecord
                            != forwardWords.length / forwardWordsPerRecord
                    || nodeWords.length / nodeWordsPerRecord
                            != reverseWords.length / reverseWordsPerRecord) {
                throw new IllegalArgumentException("World light node streams disagree");
            }
            this.nodeWordCount = nodeWords.length;
            this.forwardWordCount = forwardWords.length;
            this.packedWords = Arrays.copyOf(
                    nodeWords,
                    nodeWords.length + forwardWords.length + reverseWords.length);
            System.arraycopy(
                    forwardWords,
                    0,
                    this.packedWords,
                    nodeWords.length,
                    forwardWords.length);
            System.arraycopy(
                    reverseWords,
                    0,
                    this.packedWords,
                    nodeWords.length + forwardWords.length,
                    reverseWords.length);
            this.leafNodes = leafNodes;
        }

        private Result(
                int[] packedWords,
                int nodeWordCount,
                int forwardWordCount,
                int[] leafNodes) {
            this.packedWords = packedWords;
            this.nodeWordCount = nodeWordCount;
            this.forwardWordCount = forwardWordCount;
            this.leafNodes = leafNodes;
        }

        private static Result fromTree(CpuLightTree.Result tree, int[] leafNodes) {
            int nodeWordCount = tree.nodeCount()
                    * (ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES);
            int forwardWordCount = tree.nodeCount()
                    * (ShaderAbi.LIGHT_NODE_FORWARD_SIZE / Integer.BYTES);
            int reverseWordCount = tree.nodeCount()
                    * (ShaderAbi.LIGHT_NODE_REVERSE_SIZE / Integer.BYTES);
            int[] packedWords = new int[nodeWordCount + forwardWordCount + reverseWordCount];
            tree.packInto(
                    packedWords,
                    0,
                    nodeWordCount,
                    nodeWordCount + forwardWordCount);
            return new Result(packedWords, nodeWordCount, forwardWordCount, leafNodes);
        }

        boolean isEmpty() {
            return this.nodeWordCount == 0;
        }

        int[] pack() {
            return this.packedWords;
        }

        long forwardByteOffset() {
            return (long) this.nodeWordCount * Integer.BYTES;
        }

        long reverseByteOffset() {
            return (long) (this.nodeWordCount + this.forwardWordCount) * Integer.BYTES;
        }

        int nodeCount() {
            return this.nodeWordCount / (ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES);
        }

        int leafNode(int clusterIndex) {
            return this.leafNodes[clusterIndex];
        }
    }
}
