package dev.prime.render.terrain;

import dev.prime.render.shader.ShaderAbi;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;
import java.util.List;

/**
 * Render-thread-owned world light tree with stable leaf slots and incremental refit.
 *
 * <p>Vacant leaves have zero power, so forward and reverse traversal assign them zero probability.
 * Rebuild occurs only for capacity/utilization changes or a measured 1.5x SAH-cost regression.
 */
final class CpuWorldLightTree {
    private static final double REBUILD_COST_RATIO = 1.5;

    private final Long2IntOpenHashMap slots = new Long2IntOpenHashMap();
    private CpuLightTree.Result tree;
    private long[] slotKeys = new long[0];
    private boolean[] active = new boolean[0];
    private boolean[] seen = new boolean[0];
    private int[] freeSlots = new int[0];
    private int freeCount;
    private int activeCount;
    private double baselineCost;
    private Result result = Result.empty();

    CpuWorldLightTree() {}

    Result update(List<GpuCluster> clusters, int originX, int originY, int originZ) {
        int lightCount = lightCount(clusters);
        if (lightCount == 0) {
            clear(clusters.size());
            return this.result;
        }
        int capacity = this.active.length;
        if (this.tree == null
                || lightCount > capacity
                || (long) lightCount * 2L < capacity
                || newKeyCount(clusters) > this.freeCount) {
            rebuild(clusters, originX, originY, originZ, lightCount);
            return this.result;
        }

        Arrays.fill(this.seen, false);
        this.result.prepare(this.tree, clusters.size());
        for (int clusterIndex = 0; clusterIndex < clusters.size(); clusterIndex++) {
            GpuCluster cluster = clusters.get(clusterIndex);
            if (cluster.lights().isEmpty()) {
                continue;
            }
            int slot = this.slots.getOrDefault(cluster.key(), CpuLightTree.NO_INDEX);
            if (slot == CpuLightTree.NO_INDEX) {
                slot = this.freeSlots[--this.freeCount];
                this.slots.put(cluster.key(), slot);
                this.slotKeys[slot] = cluster.key();
                this.active[slot] = true;
                this.activeCount++;
            }
            this.seen[slot] = true;
            setLeaf(slot, clusterIndex, cluster, originX, originY, originZ);
            this.result.setLeafNode(clusterIndex, this.tree.leafNode(slot));
        }
        for (int slot = 0; slot < this.active.length; slot++) {
            if (this.active[slot] && !this.seen[slot]) {
                this.slots.remove(this.slotKeys[slot]);
                this.active[slot] = false;
                this.freeSlots[this.freeCount++] = slot;
                this.activeCount--;
                this.tree.deactivateLeaf(slot);
            }
        }
        this.tree.refit();
        this.result.pack(this.tree);
        double cost = this.tree.treeCost();
        if (this.activeCount > 1
                && (this.baselineCost == 0.0
                        || cost > this.baselineCost * REBUILD_COST_RATIO)) {
            rebuild(clusters, originX, originY, originZ, lightCount);
        }
        return this.result;
    }

    Result result() {
        return this.result;
    }

    private void rebuild(
            List<GpuCluster> clusters,
            int originX,
            int originY,
            int originZ,
            int lightCount) {
        int reserve = Math.max(1, lightCount / 8);
        int capacity = Math.addExact(lightCount, reserve);
        CpuLightTree.Leaves leaves = new CpuLightTree.Leaves(capacity);
        float placeholderMinX = 0.0F;
        float placeholderMinY = 0.0F;
        float placeholderMinZ = 0.0F;
        float placeholderMaxX = 0.0F;
        float placeholderMaxY = 0.0F;
        float placeholderMaxZ = 0.0F;
        boolean hasPlaceholder = false;
        this.slots.clear();
        this.slotKeys = new long[capacity];
        this.active = new boolean[capacity];
        this.seen = new boolean[capacity];
        this.freeSlots = new int[capacity];
        this.freeCount = 0;
        this.activeCount = 0;

        for (int clusterIndex = 0; clusterIndex < clusters.size(); clusterIndex++) {
            GpuCluster cluster = clusters.get(clusterIndex);
            if (cluster.lights().isEmpty()) {
                continue;
            }
            int slot = this.activeCount++;
            CpuLightTree.Bounds bounds = cluster.lights().bounds();
            float translateX = (cluster.clusterX() << 4) - originX;
            float translateY = (cluster.clusterY() << 4) - originY;
            float translateZ = (cluster.clusterZ() << 4) - originZ;
            float minX = bounds.minX() + translateX;
            float minY = bounds.minY() + translateY;
            float minZ = bounds.minZ() + translateZ;
            float maxX = bounds.maxX() + translateX;
            float maxY = bounds.maxY() + translateY;
            float maxZ = bounds.maxZ() + translateZ;
            if (!hasPlaceholder) {
                placeholderMinX = minX;
                placeholderMinY = minY;
                placeholderMinZ = minZ;
                placeholderMaxX = maxX;
                placeholderMaxY = maxY;
                placeholderMaxZ = maxZ;
                hasPlaceholder = true;
            }
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
                    cluster.lights().power(),
                    slot);
            this.slots.put(cluster.key(), slot);
            this.slotKeys[slot] = cluster.key();
            this.active[slot] = true;
        }
        for (int slot = this.activeCount; slot < capacity; slot++) {
            leaves.addInactive(
                    placeholderMinX,
                    placeholderMinY,
                    placeholderMinZ,
                    placeholderMaxX,
                    placeholderMaxY,
                    placeholderMaxZ,
                    slot);
            this.freeSlots[this.freeCount++] = slot;
        }

        this.tree = CpuLightTree.buildOwned(
                leaves, capacity, CpuLightTree.WORLD_SOFTENING_SCALE);
        this.result = Result.forTree(this.tree, clusters.size());
        for (int clusterIndex = 0; clusterIndex < clusters.size(); clusterIndex++) {
            GpuCluster cluster = clusters.get(clusterIndex);
            if (cluster.lights().isEmpty()) {
                continue;
            }
            int slot = this.slots.getOrDefault(cluster.key(), CpuLightTree.NO_INDEX);
            setLeaf(slot, clusterIndex, cluster, originX, originY, originZ);
            this.result.setLeafNode(clusterIndex, this.tree.leafNode(slot));
        }
        for (int slot = this.activeCount; slot < capacity; slot++) {
            this.tree.deactivateLeaf(slot);
        }
        this.tree.refit();
        this.baselineCost = this.tree.treeCost();
        this.result.pack(this.tree);
    }

    private int newKeyCount(List<GpuCluster> clusters) {
        int count = 0;
        for (GpuCluster cluster : clusters) {
            if (!cluster.lights().isEmpty()
                    && this.slots.getOrDefault(cluster.key(), CpuLightTree.NO_INDEX)
                            == CpuLightTree.NO_INDEX) {
                count++;
            }
        }
        return count;
    }

    private void setLeaf(
            int slot,
            int clusterIndex,
            GpuCluster cluster,
            int originX,
            int originY,
            int originZ) {
        CpuLightTree.Bounds bounds = cluster.lights().bounds();
        float translateX = (cluster.clusterX() << 4) - originX;
        float translateY = (cluster.clusterY() << 4) - originY;
        float translateZ = (cluster.clusterZ() << 4) - originZ;
        this.tree.setLeaf(
                slot,
                bounds.minX() + translateX,
                bounds.minY() + translateY,
                bounds.minZ() + translateZ,
                bounds.maxX() + translateX,
                bounds.maxY() + translateY,
                bounds.maxZ() + translateZ,
                cluster.lights().power(),
                clusterIndex);
    }

    private static int lightCount(List<GpuCluster> clusters) {
        int count = 0;
        for (GpuCluster cluster : clusters) {
            if (!cluster.lights().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private void clear(int clusterCount) {
        this.slots.clear();
        this.tree = null;
        this.slotKeys = new long[0];
        this.active = new boolean[0];
        this.seen = new boolean[0];
        this.freeSlots = new int[0];
        this.freeCount = 0;
        this.activeCount = 0;
        this.baselineCost = 0.0;
        this.result.prepareEmpty(clusterCount);
    }

    static final class Result {
        private int[] packedWords;
        private int nodeWordCount;
        private int forwardWordCount;
        private int[] leafNodes;
        private int clusterCount;

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
            this.clusterCount = leafNodes.length;
        }

        private Result(int[] packedWords, int nodeWordCount, int forwardWordCount, int clusterCapacity) {
            this.packedWords = packedWords;
            this.nodeWordCount = nodeWordCount;
            this.forwardWordCount = forwardWordCount;
            this.leafNodes = new int[clusterCapacity];
        }

        private static Result empty() {
            return new Result(new int[0], 0, 0, 0);
        }

        private void prepareEmpty(int count) {
            if (count < 0) {
                throw new IllegalArgumentException("Negative world light cluster count");
            }
            if (this.packedWords.length != 0) {
                this.packedWords = new int[0];
            }
            this.nodeWordCount = 0;
            this.forwardWordCount = 0;
            if (this.leafNodes.length < count) {
                this.leafNodes = new int[count];
            }
            Arrays.fill(this.leafNodes, 0, count, CpuLightTree.NO_INDEX);
            this.clusterCount = count;
        }

        private static Result forTree(CpuLightTree.Result tree, int clusterCount) {
            int nodeWordCount = tree.nodeCount()
                    * (ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES);
            int forwardWordCount = tree.nodeCount()
                    * (ShaderAbi.LIGHT_NODE_FORWARD_SIZE / Integer.BYTES);
            int reverseWordCount = tree.nodeCount()
                    * (ShaderAbi.LIGHT_NODE_REVERSE_SIZE / Integer.BYTES);
            Result result = new Result(
                    new int[nodeWordCount + forwardWordCount + reverseWordCount],
                    nodeWordCount,
                    forwardWordCount,
                    clusterCount);
            result.prepare(tree, clusterCount);
            return result;
        }

        private void prepare(CpuLightTree.Result tree, int count) {
            if (tree.nodeCount() != nodeCount()) {
                throw new IllegalArgumentException("World light tree topology changed without replacement");
            }
            if (this.leafNodes.length < count) {
                this.leafNodes = new int[count];
            }
            Arrays.fill(this.leafNodes, 0, count, CpuLightTree.NO_INDEX);
            this.clusterCount = count;
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
            if (clusterIndex < 0 || clusterIndex >= this.clusterCount) {
                throw new IndexOutOfBoundsException(clusterIndex);
            }
            return this.leafNodes[clusterIndex];
        }
    }
}
