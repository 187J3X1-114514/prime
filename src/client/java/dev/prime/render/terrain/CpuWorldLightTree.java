package dev.prime.render.terrain;

import dev.prime.render.shader.ShaderAbi;
import java.util.Arrays;

/** Pure CPU builder for the packed top-level world light tree. */
final class CpuWorldLightTree {
    private CpuWorldLightTree() {}

    static Result build(WorldLightTreeInput input) {
        int lightCount = lightCount(input);
        if (lightCount == 0) {
            return Result.empty(input.clusterCount());
        }

        CpuLightTree.Leaves leaves = new CpuLightTree.Leaves(lightCount);
        for (int clusterIndex = 0; clusterIndex < input.clusterCount(); clusterIndex++) {
            CompiledClusterLights.Summary lights = input.lights(clusterIndex);
            if (lights.isEmpty()) {
                continue;
            }
            CpuLightTree.Bounds bounds = lights.bounds();
            float translateX = (input.clusterX(clusterIndex) << 4) - input.originX();
            float translateY = (input.clusterY(clusterIndex) << 4) - input.originY();
            float translateZ = (input.clusterZ(clusterIndex) << 4) - input.originZ();
            float minX = bounds.minX() + translateX;
            float minY = bounds.minY() + translateY;
            float minZ = bounds.minZ() + translateZ;
            float maxX = bounds.maxX() + translateX;
            float maxY = bounds.maxY() + translateY;
            float maxZ = bounds.maxZ() + translateZ;
            leaves.add(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    (minX + maxX) * 0.5F,
                    (minY + maxY) * 0.5F,
                    (minZ + maxZ) * 0.5F,
                    lights.power(),
                    clusterIndex,
                    LightDirection.unpack(lights.packedDirection()));
        }

        CpuLightTree.Result tree = CpuLightTree.buildOwned(
                leaves, input.clusterCount(), CpuLightTree.WORLD_SOFTENING_SCALE);
        Result result = Result.forTree(tree, input.clusterCount());
        for (int clusterIndex = 0; clusterIndex < input.clusterCount(); clusterIndex++) {
            result.setLeafNode(clusterIndex, tree.leafNode(clusterIndex));
        }
        result.pack(tree);
        return result;
    }

    private static int lightCount(WorldLightTreeInput input) {
        int count = 0;
        for (int index = 0; index < input.clusterCount(); index++) {
            if (!input.lights(index).isEmpty()) {
                count++;
            }
        }
        return count;
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
            this.leafNodes = leafNodes.clone();
        }

        private Result(
                int[] packedWords,
                int nodeWordCount,
                int forwardWordCount,
                int clusterCount) {
            this.packedWords = packedWords;
            this.nodeWordCount = nodeWordCount;
            this.forwardWordCount = forwardWordCount;
            this.leafNodes = new int[clusterCount];
            Arrays.fill(this.leafNodes, CpuLightTree.NO_INDEX);
        }

        static Result empty(int clusterCount) {
            if (clusterCount < 0) {
                throw new IllegalArgumentException("Negative world light cluster count");
            }
            return new Result(new int[0], 0, 0, clusterCount);
        }

        private static Result forTree(CpuLightTree.Result tree, int clusterCount) {
            int nodeWordCount = tree.nodeCount()
                    * (ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES);
            int forwardWordCount = tree.nodeCount()
                    * (ShaderAbi.LIGHT_NODE_FORWARD_SIZE / Integer.BYTES);
            int reverseWordCount = tree.nodeCount()
                    * (ShaderAbi.LIGHT_NODE_REVERSE_SIZE / Integer.BYTES);
            return new Result(
                    new int[nodeWordCount + forwardWordCount + reverseWordCount],
                    nodeWordCount,
                    forwardWordCount,
                    clusterCount);
        }

        private void setLeafNode(int clusterIndex, int leafNode) {
            this.leafNodes[clusterIndex] = leafNode;
        }

        private void pack(CpuLightTree.Result tree) {
            tree.packInto(
                    this.packedWords,
                    0,
                    this.nodeWordCount,
                    this.nodeWordCount + this.forwardWordCount);
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
            if (clusterIndex < 0 || clusterIndex >= this.leafNodes.length) {
                throw new IndexOutOfBoundsException(clusterIndex);
            }
            return this.leafNodes[clusterIndex];
        }
    }
}
