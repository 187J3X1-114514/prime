package dev.prime.render.terrain;

import java.util.Arrays;
import java.util.List;

/**
 * Pure CPU builder for both levels of Prime's light tree.
 *
 * <p>Selection uses the same packed node power and bounds in both directions on the GPU. Bounds
 * and traversal metadata are emitted as separate streams: forward sampling reads both child bounds,
 * child-or-leaf indices and packed emission bounds. Reverse MIS reads a separate exact parent
 * stream and derives the sibling from the consecutive-pair invariant instead of reconstructing
 * probabilities from higher-precision CPU state.
 */
final class CpuLightTree {
    static final int NO_INDEX = -1;
    static final float LOCAL_SOFTENING_SCALE = 1.0F / 128.0F;
    static final float WORLD_SOFTENING_SCALE = 1.0F / 64.0F;
    static final float MINIMUM_SOFTENING_DISTANCE_SQUARED = 0.25F;
    static final int LEAF_FLAG = Integer.MIN_VALUE;
    static final int INDEX_MASK = Integer.MAX_VALUE;
    private static final int SAH_BIN_COUNT = 16;
    private static final int BOUNDS_WORDS_PER_NODE = 8;
    private static final int FORWARD_WORDS_PER_NODE = 2;
    private static final int REVERSE_WORDS_PER_NODE = 1;
    private CpuLightTree() {
    }

    static Result build(List<Leaf> source, int indexCapacity, float softeningScale) {
        Leaves leaves = new Leaves(source.size());
        for (Leaf leaf : source) {
            leaves.add(
                    leaf.bounds,
                    leaf.centerX,
                    leaf.centerY,
                    leaf.centerZ,
                    leaf.power,
                    leaf.index,
                    leaf.direction);
        }
        return buildOwned(leaves, indexCapacity, softeningScale);
    }

    static Result buildOwned(
            Leaves leaves, int indexCapacity, float softeningScale) {
        if (leaves.size == 0) {
            throw new IllegalArgumentException("A light tree requires at least one leaf");
        }
        if (indexCapacity < 0) {
            throw new IllegalArgumentException("Negative light leaf index capacity");
        }
        Nodes nodes = new Nodes(leaves.size * 2 - 1);
        int[] leafNodes = new int[indexCapacity];
        Arrays.fill(leafNodes, NO_INDEX);
        Workspace workspace = new Workspace();
        int rootNode = createNode(
                leaves, 0, leaves.size, NO_INDEX, softeningScale, nodes);
        populateNode(
                leaves,
                0,
                leaves.size,
                rootNode,
                softeningScale,
                nodes,
                leafNodes,
                workspace);
        return new Result(nodes, leafNodes, softeningScale);
    }

    /**
     * Populates a node whose aggregate data has already been allocated.
     *
     * <p>Both direct children are appended before either subtree is populated. Every sibling pair
     * is therefore consecutive in the packed arrays, so a traversal's mandatory two-child read is
     * spatially coherent without changing the SAH partition or any sampling probability.
     */
    private static void populateNode(
            Leaves leaves,
            int start,
            int end,
            int nodeIndex,
            float softeningScale,
            Nodes nodes,
            int[] leafNodes,
            Workspace workspace) {
        int count = end - start;
        if (count <= 0) {
            throw new IllegalStateException("Empty light tree range");
        }
        if (count == 1) {
            int leaf = leaves.index[start];
            if (leaf < 0 || leaf >= leafNodes.length || leafNodes[leaf] != NO_INDEX) {
                throw new IllegalStateException("Invalid or duplicate light leaf index " + leaf);
            }
            nodes.firstChildOrLeaf[nodeIndex] = leaf;
            nodes.direction[nodeIndex] = leaves.direction[start];
            leafNodes[leaf] = nodeIndex;
            return;
        }

        int middle = partition(leaves, start, end, workspace);
        int left = createNode(
                leaves, start, middle, nodeIndex, softeningScale, nodes);
        int right = createNode(
                leaves, middle, end, nodeIndex, softeningScale, nodes);
        nodes.firstChildOrLeaf[nodeIndex] = left;
        nodes.secondChild[nodeIndex] = right;
        populateNode(leaves, start, middle, left, softeningScale, nodes, leafNodes, workspace);
        populateNode(leaves, middle, end, right, softeningScale, nodes, leafNodes, workspace);
        nodes.refitDirection(nodeIndex);
    }

    private static int createNode(
            Leaves leaves,
            int start,
            int end,
            int parent,
            float softeningScale,
            Nodes nodes) {
        return nodes.add(leaves, start, end, softeningScale, parent);
    }

    private static int partition(Leaves leaves, int start, int end, Workspace workspace) {
        workspace.findCentroidBounds(leaves, start, end);
        float bestCost = Float.POSITIVE_INFINITY;
        int bestAxis = -1;
        int bestSplit = -1;
        for (int axis = 0; axis < 3; axis++) {
            float minimum = workspace.centroidMinimum(axis);
            float extent = workspace.centroidMaximum(axis) - minimum;
            if (!(extent > 0.0F)) {
                continue;
            }
            Bin[] bins = workspace.bins;
            for (Bin bin : bins) {
                bin.reset();
            }
            for (int index = start; index < end; index++) {
                int binIndex = binIndex(leaves.center(index, axis), minimum, extent);
                bins[binIndex].include(leaves, index);
            }
            workspace.aggregate();
            for (int split = 0; split < SAH_BIN_COUNT - 1; split++) {
                int leftCount = workspace.prefixCount[split];
                int rightCount = workspace.suffixCount[split + 1];
                if (leftCount == 0 || rightCount == 0) {
                    continue;
                }
                float cost = surfaceArea(
                                workspace.prefixMinX[split],
                                workspace.prefixMinY[split],
                                workspace.prefixMinZ[split],
                                workspace.prefixMaxX[split],
                                workspace.prefixMaxY[split],
                                workspace.prefixMaxZ[split])
                                * workspace.prefixPower[split]
                        + surfaceArea(
                                workspace.suffixMinX[split + 1],
                                workspace.suffixMinY[split + 1],
                                workspace.suffixMinZ[split + 1],
                                workspace.suffixMaxX[split + 1],
                                workspace.suffixMaxY[split + 1],
                                workspace.suffixMaxZ[split + 1])
                                * workspace.suffixPower[split + 1];
                if (cost < bestCost) {
                    bestCost = cost;
                    bestAxis = axis;
                    bestSplit = split;
                }
            }
        }

        if (bestAxis >= 0) {
            float minimum = workspace.centroidMinimum(bestAxis);
            float extent = workspace.centroidMaximum(bestAxis) - minimum;
            int left = start;
            int right = end - 1;
            while (left <= right) {
                if (binIndex(leaves.center(left, bestAxis), minimum, extent) <= bestSplit) {
                    left++;
                } else {
                    leaves.swap(left, right);
                    right--;
                }
            }
            if (left > start && left < end) {
                return left;
            }
        }

        int fallbackAxis = workspace.longestCentroidAxis();
        sortByAxis(leaves, start, end, fallbackAxis);
        return start + (end - start) / 2;
    }

    private static void sortByAxis(Leaves leaves, int start, int end, int axis) {
        int count = end - start;
        for (int root = count / 2 - 1; root >= 0; root--) {
            siftDown(leaves, start, root, count, axis);
        }
        for (int last = count - 1; last > 0; last--) {
            leaves.swap(start, start + last);
            siftDown(leaves, start, 0, last, axis);
        }
    }

    private static void siftDown(
            Leaves leaves, int start, int root, int count, int axis) {
        while (true) {
            int child = root * 2 + 1;
            if (child >= count) {
                return;
            }
            if (child + 1 < count
                    && leaves.compare(start + child, start + child + 1, axis) < 0) {
                child++;
            }
            if (leaves.compare(start + root, start + child, axis) >= 0) {
                return;
            }
            leaves.swap(start + root, start + child);
            root = child;
        }
    }

    private static int binIndex(float center, float minimum, float extent) {
        float scaled = Math.max(0.0F, Math.min(Math.nextDown(1.0F), (center - minimum) / extent));
        return Math.min((int) (scaled * SAH_BIN_COUNT), SAH_BIN_COUNT - 1);
    }

    private static float surfaceArea(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ) {
        float x = Math.max(maxX - minX, 0.0F);
        float y = Math.max(maxY - minY, 0.0F);
        float z = Math.max(maxZ - minZ, 0.0F);
        return 2.0F * (x * y + y * z + z * x);
    }

    static record Leaf(
            Bounds bounds,
            float centerX,
            float centerY,
            float centerZ,
            float power,
            int index,
            LightDirection.Bounds direction) {
        Leaf(Bounds bounds, float centerX, float centerY, float centerZ, float power, int index) {
            this(bounds, centerX, centerY, centerZ, power, index, LightDirection.full());
        }

        Leaf {
            validateLeaf(bounds, centerX, centerY, centerZ, power);
            if (direction == null) {
                throw new IllegalArgumentException("Light direction must not be null");
            }
        }
    }

    static record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    }

    static final class Result {
        private final Nodes nodes;
        private final int[] leafNodes;
        private final float softeningScale;

        private Result(Nodes nodes, int[] leafNodes, float softeningScale) {
            this.nodes = nodes;
            this.leafNodes = leafNodes;
            this.softeningScale = softeningScale;
        }

        Result copy() {
            return new Result(
                    this.nodes.copy(),
                    this.leafNodes.clone(),
                    this.softeningScale);
        }

        int leafCapacity() {
            return this.leafNodes.length;
        }

        void setLeaf(
                int slot,
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                float power,
                int outputIndex) {
            validateLeaf(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    (minX + maxX) * 0.5F,
                    (minY + maxY) * 0.5F,
                    (minZ + maxZ) * 0.5F,
                    power);
            int node = this.leafNodes[slot];
            if (this.nodes.secondChild[node] != NO_INDEX) {
                throw new IllegalStateException("Light slot does not reference a leaf");
            }
            this.nodes.setBounds(
                    node, minX, minY, minZ, maxX, maxY, maxZ, power, this.softeningScale);
            this.nodes.direction[node] = LightDirection.full();
            this.nodes.firstChildOrLeaf[node] = outputIndex;
        }

        void deactivateLeaf(int slot) {
            int node = this.leafNodes[slot];
            if (this.nodes.secondChild[node] != NO_INDEX) {
                throw new IllegalStateException("Light slot does not reference a leaf");
            }
            this.nodes.power[node] = 0.0F;
            this.nodes.direction[node] = LightDirection.full();
            this.nodes.firstChildOrLeaf[node] = 0;
        }

        void refit() {
            for (int node = this.nodes.size - 1; node >= 0; node--) {
                if (this.nodes.secondChild[node] != NO_INDEX) {
                    this.nodes.refit(node, this.softeningScale);
                }
            }
        }

        double treeCost() {
            double cost = 0.0;
            for (int node = 0; node < this.nodes.size; node++) {
                if (this.nodes.secondChild[node] != NO_INDEX) {
                    cost += (double) surfaceArea(
                                    this.nodes.minX[node],
                                    this.nodes.minY[node],
                                    this.nodes.minZ[node],
                                    this.nodes.maxX[node],
                                    this.nodes.maxY[node],
                                    this.nodes.maxZ[node])
                            * this.nodes.power[node];
                }
            }
            return cost;
        }

        int[] packNodeBounds() {
            int[] result = new int[this.nodes.size * BOUNDS_WORDS_PER_NODE];
            int cursor = 0;
            for (int node = 0; node < this.nodes.size; node++) {
                result[cursor++] = Float.floatToRawIntBits(this.nodes.minX[node]);
                result[cursor++] = Float.floatToRawIntBits(this.nodes.minY[node]);
                result[cursor++] = Float.floatToRawIntBits(this.nodes.minZ[node]);
                result[cursor++] = Float.floatToRawIntBits(this.nodes.power[node]);
                result[cursor++] = Float.floatToRawIntBits(this.nodes.maxX[node]);
                result[cursor++] = Float.floatToRawIntBits(this.nodes.maxY[node]);
                result[cursor++] = Float.floatToRawIntBits(this.nodes.maxZ[node]);
                result[cursor++] = Float.floatToRawIntBits(
                        this.nodes.softeningDistanceSquared[node]);
            }
            return result;
        }

        int[] packNodeForward() {
            int[] result = new int[this.nodes.size * FORWARD_WORDS_PER_NODE];
            int cursor = 0;
            for (int node = 0; node < this.nodes.size; node++) {
                int firstChildOrLeaf = this.nodes.firstChildOrLeaf[node];
                int secondChild = this.nodes.secondChild[node];
                if (firstChildOrLeaf < 0) {
                    throw new IllegalStateException("Light tree node was not populated");
                }
                if (secondChild == NO_INDEX) {
                    result[cursor++] = firstChildOrLeaf | LEAF_FLAG;
                } else {
                    if (secondChild != firstChildOrLeaf + 1) {
                        throw new IllegalStateException("Light tree siblings must be consecutive");
                    }
                    result[cursor++] = firstChildOrLeaf;
                }
                result[cursor++] = LightDirection.pack(this.nodes.direction[node]);
            }
            return result;
        }

        int[] packNodeReverse() {
            int[] result = new int[this.nodes.size * REVERSE_WORDS_PER_NODE];
            int cursor = 0;
            for (int node = 0; node < this.nodes.size; node++) {
                result[cursor++] = this.nodes.parent[node];
            }
            return result;
        }

        void packInto(
                int[] target,
                int boundsWordOffset,
                int forwardWordOffset,
                int reverseWordOffset) {
            int boundsCursor = boundsWordOffset;
            int forwardCursor = forwardWordOffset;
            int reverseCursor = reverseWordOffset;
            for (int node = 0; node < this.nodes.size; node++) {
                target[boundsCursor++] = Float.floatToRawIntBits(this.nodes.minX[node]);
                target[boundsCursor++] = Float.floatToRawIntBits(this.nodes.minY[node]);
                target[boundsCursor++] = Float.floatToRawIntBits(this.nodes.minZ[node]);
                target[boundsCursor++] = Float.floatToRawIntBits(this.nodes.power[node]);
                target[boundsCursor++] = Float.floatToRawIntBits(this.nodes.maxX[node]);
                target[boundsCursor++] = Float.floatToRawIntBits(this.nodes.maxY[node]);
                target[boundsCursor++] = Float.floatToRawIntBits(this.nodes.maxZ[node]);
                target[boundsCursor++] = Float.floatToRawIntBits(
                        this.nodes.softeningDistanceSquared[node]);
                int firstChildOrLeaf = this.nodes.firstChildOrLeaf[node];
                int secondChild = this.nodes.secondChild[node];
                if (firstChildOrLeaf < 0) {
                    throw new IllegalStateException("Light tree node was not populated");
                }
                if (secondChild == NO_INDEX) {
                    target[forwardCursor++] = firstChildOrLeaf | LEAF_FLAG;
                } else {
                    if (secondChild != firstChildOrLeaf + 1) {
                        throw new IllegalStateException("Light tree siblings must be consecutive");
                    }
                    target[forwardCursor++] = firstChildOrLeaf;
                }
                target[forwardCursor++] = LightDirection.pack(this.nodes.direction[node]);
                target[reverseCursor++] = this.nodes.parent[node];
            }
        }

        int leafNode(int leafIndex) {
            return this.leafNodes[leafIndex];
        }

        Bounds bounds() {
            return this.nodes.bounds(0);
        }

        float power() {
            return this.nodes.power[0];
        }

        int packedDirection() {
            return LightDirection.pack(this.nodes.direction[0]);
        }

        int nodeCount() {
            return this.nodes.size;
        }
    }

    static final class Leaves {
        private final float[] minX;
        private final float[] minY;
        private final float[] minZ;
        private final float[] maxX;
        private final float[] maxY;
        private final float[] maxZ;
        private final float[] centerX;
        private final float[] centerY;
        private final float[] centerZ;
        private final float[] power;
        private final int[] index;
        private final LightDirection.Bounds[] direction;
        int size;

        Leaves(int capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException("Negative light leaf capacity");
            }
            this.minX = new float[capacity];
            this.minY = new float[capacity];
            this.minZ = new float[capacity];
            this.maxX = new float[capacity];
            this.maxY = new float[capacity];
            this.maxZ = new float[capacity];
            this.centerX = new float[capacity];
            this.centerY = new float[capacity];
            this.centerZ = new float[capacity];
            this.power = new float[capacity];
            this.index = new int[capacity];
            this.direction = new LightDirection.Bounds[capacity];
        }

        void add(
                Bounds bounds,
                float centerX,
                float centerY,
                float centerZ,
                float power,
                int index) {
            add(bounds, centerX, centerY, centerZ, power, index, LightDirection.full());
        }

        void add(
                Bounds bounds,
                float centerX,
                float centerY,
                float centerZ,
                float power,
                int index,
                LightDirection.Bounds direction) {
            if (bounds == null) {
                throw new IllegalArgumentException("Light bounds must not be null");
            }
            add(
                    bounds.minX,
                    bounds.minY,
                    bounds.minZ,
                    bounds.maxX,
                    bounds.maxY,
                    bounds.maxZ,
                    centerX,
                    centerY,
                    centerZ,
                    power,
                    index,
                    direction);
        }

        void addInactive(
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                int index) {
            validateBounds(minX, minY, minZ, maxX, maxY, maxZ);
            append(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    (minX + maxX) * 0.5F,
                    (minY + maxY) * 0.5F,
                    (minZ + maxZ) * 0.5F,
                    0.0F,
                    index,
                    LightDirection.full());
        }

        void add(
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                float centerX,
                float centerY,
                float centerZ,
                float power,
                int index) {
            add(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    centerX,
                    centerY,
                    centerZ,
                    power,
                    index,
                    LightDirection.full());
        }

        void add(
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                float centerX,
                float centerY,
                float centerZ,
                float power,
                int index,
                LightDirection.Bounds direction) {
            validateLeaf(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    centerX,
                    centerY,
                    centerZ,
                    power);
            append(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    centerX,
                    centerY,
                    centerZ,
                    power,
                    index,
                    direction);
        }

        private void append(
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                float centerX,
                float centerY,
                float centerZ,
                float power,
                int index,
                LightDirection.Bounds direction) {
            if (direction == null) {
                throw new IllegalArgumentException("Light direction must not be null");
            }
            int slot = this.size++;
            this.minX[slot] = minX;
            this.minY[slot] = minY;
            this.minZ[slot] = minZ;
            this.maxX[slot] = maxX;
            this.maxY[slot] = maxY;
            this.maxZ[slot] = maxZ;
            this.centerX[slot] = centerX;
            this.centerY[slot] = centerY;
            this.centerZ[slot] = centerZ;
            this.power[slot] = power;
            this.index[slot] = index;
            this.direction[slot] = direction;
        }

        private float center(int slot, int axis) {
            return switch (axis) {
                case 0 -> this.centerX[slot];
                case 1 -> this.centerY[slot];
                case 2 -> this.centerZ[slot];
                default -> throw new IndexOutOfBoundsException(axis);
            };
        }

        private int compare(int first, int second, int axis) {
            int compared = Float.compare(center(first, axis), center(second, axis));
            return compared != 0 ? compared : Integer.compare(this.index[first], this.index[second]);
        }

        private void swap(int first, int second) {
            if (first == second) {
                return;
            }
            swap(this.minX, first, second);
            swap(this.minY, first, second);
            swap(this.minZ, first, second);
            swap(this.maxX, first, second);
            swap(this.maxY, first, second);
            swap(this.maxZ, first, second);
            swap(this.centerX, first, second);
            swap(this.centerY, first, second);
            swap(this.centerZ, first, second);
            swap(this.power, first, second);
            LightDirection.Bounds direction = this.direction[first];
            this.direction[first] = this.direction[second];
            this.direction[second] = direction;
            int index = this.index[first];
            this.index[first] = this.index[second];
            this.index[second] = index;
        }

        private static void swap(float[] values, int first, int second) {
            float value = values[first];
            values[first] = values[second];
            values[second] = value;
        }
    }

    private static void validateLeaf(
            Bounds bounds, float centerX, float centerY, float centerZ, float power) {
        if (bounds == null) {
            throw new IllegalArgumentException("Light bounds must not be null");
        }
        validateLeaf(
                bounds.minX,
                bounds.minY,
                bounds.minZ,
                bounds.maxX,
                bounds.maxY,
                bounds.maxZ,
                centerX,
                centerY,
                centerZ,
                power);
    }

    private static void validateLeaf(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float centerX,
            float centerY,
            float centerZ,
            float power) {
        validateBounds(minX, minY, minZ, maxX, maxY, maxZ);
        if (!Float.isFinite(centerX)
                || !Float.isFinite(centerY)
                || !Float.isFinite(centerZ)) {
            throw new IllegalArgumentException("Light bounds and center must be finite and ordered");
        }
        if (!(power > 0.0F) || !Float.isFinite(power)) {
            throw new IllegalArgumentException("Light power must be finite and positive");
        }
    }

    private static void validateBounds(
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ) {
        if (!Float.isFinite(minX)
                || !Float.isFinite(minY)
                || !Float.isFinite(minZ)
                || !Float.isFinite(maxX)
                || !Float.isFinite(maxY)
                || !Float.isFinite(maxZ)
                || minX > maxX
                || minY > maxY
                || minZ > maxZ) {
            throw new IllegalArgumentException("Light bounds must be finite and ordered");
        }
    }

    private static final class Nodes {
        private final float[] minX;
        private final float[] minY;
        private final float[] minZ;
        private final float[] maxX;
        private final float[] maxY;
        private final float[] maxZ;
        private final float[] power;
        private final float[] softeningDistanceSquared;
        private final int[] parent;
        private final int[] firstChildOrLeaf;
        private final int[] secondChild;
        private final LightDirection.Bounds[] direction;
        private int size;

        private Nodes(int capacity) {
            this.minX = new float[capacity];
            this.minY = new float[capacity];
            this.minZ = new float[capacity];
            this.maxX = new float[capacity];
            this.maxY = new float[capacity];
            this.maxZ = new float[capacity];
            this.power = new float[capacity];
            this.softeningDistanceSquared = new float[capacity];
            this.parent = new int[capacity];
            this.firstChildOrLeaf = new int[capacity];
            this.secondChild = new int[capacity];
            this.direction = new LightDirection.Bounds[capacity];
            Arrays.fill(this.firstChildOrLeaf, NO_INDEX);
            Arrays.fill(this.secondChild, NO_INDEX);
            Arrays.fill(this.direction, LightDirection.full());
        }

        private Nodes copy() {
            Nodes copy = new Nodes(this.minX.length);
            System.arraycopy(this.minX, 0, copy.minX, 0, this.minX.length);
            System.arraycopy(this.minY, 0, copy.minY, 0, this.minY.length);
            System.arraycopy(this.minZ, 0, copy.minZ, 0, this.minZ.length);
            System.arraycopy(this.maxX, 0, copy.maxX, 0, this.maxX.length);
            System.arraycopy(this.maxY, 0, copy.maxY, 0, this.maxY.length);
            System.arraycopy(this.maxZ, 0, copy.maxZ, 0, this.maxZ.length);
            System.arraycopy(this.power, 0, copy.power, 0, this.power.length);
            System.arraycopy(
                    this.softeningDistanceSquared,
                    0,
                    copy.softeningDistanceSquared,
                    0,
                    this.softeningDistanceSquared.length);
            System.arraycopy(this.parent, 0, copy.parent, 0, this.parent.length);
            System.arraycopy(
                    this.firstChildOrLeaf,
                    0,
                    copy.firstChildOrLeaf,
                    0,
                    this.firstChildOrLeaf.length);
            System.arraycopy(
                    this.secondChild,
                    0,
                    copy.secondChild,
                    0,
                    this.secondChild.length);
            System.arraycopy(
                    this.direction,
                    0,
                    copy.direction,
                    0,
                    this.direction.length);
            copy.size = this.size;
            return copy;
        }

        private int add(
                Leaves leaves,
                int start,
                int end,
                float softeningScale,
                int parent) {
            int index = this.size++;
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            float power = 0.0F;
            for (int leaf = start; leaf < end; leaf++) {
                float leafPower = leaves.power[leaf];
                if (leafPower > 0.0F) {
                    minX = Math.min(minX, leaves.minX[leaf]);
                    minY = Math.min(minY, leaves.minY[leaf]);
                    minZ = Math.min(minZ, leaves.minZ[leaf]);
                    maxX = Math.max(maxX, leaves.maxX[leaf]);
                    maxY = Math.max(maxY, leaves.maxY[leaf]);
                    maxZ = Math.max(maxZ, leaves.maxZ[leaf]);
                    power += leafPower;
                }
            }
            if (power == 0.0F) {
                minX = leaves.minX[start];
                minY = leaves.minY[start];
                minZ = leaves.minZ[start];
                maxX = leaves.maxX[start];
                maxY = leaves.maxY[start];
                maxZ = leaves.maxZ[start];
            } else if (!Float.isFinite(power)) {
                throw new IllegalArgumentException("Aggregate light power exceeds f32 range");
            }
            setBounds(index, minX, minY, minZ, maxX, maxY, maxZ, power, softeningScale);
            this.parent[index] = parent;
            return index;
        }

        private void refit(int index, float softeningScale) {
            int first = this.firstChildOrLeaf[index];
            int second = this.secondChild[index];
            float firstPower = this.power[first];
            float secondPower = this.power[second];
            float combinedPower = firstPower + secondPower;
            if (!Float.isFinite(combinedPower)) {
                throw new IllegalArgumentException("Aggregate light power exceeds f32 range");
            }
            if (firstPower > 0.0F && secondPower > 0.0F) {
                setBounds(
                        index,
                        Math.min(this.minX[first], this.minX[second]),
                        Math.min(this.minY[first], this.minY[second]),
                        Math.min(this.minZ[first], this.minZ[second]),
                        Math.max(this.maxX[first], this.maxX[second]),
                        Math.max(this.maxY[first], this.maxY[second]),
                        Math.max(this.maxZ[first], this.maxZ[second]),
                        combinedPower,
                        softeningScale);
            } else {
                int source = firstPower > 0.0F ? first : second;
                setBounds(
                        index,
                        this.minX[source],
                        this.minY[source],
                        this.minZ[source],
                        this.maxX[source],
                        this.maxY[source],
                        this.maxZ[source],
                        combinedPower,
                        softeningScale);
            }
            refitDirection(index);
        }

        private void refitDirection(int index) {
            int first = this.firstChildOrLeaf[index];
            int second = this.secondChild[index];
            this.direction[index] = LightDirection.combine(
                    this.direction[first],
                    this.power[first],
                    this.direction[second],
                    this.power[second]);
        }

        private void setBounds(
                int index,
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                float power,
                float softeningScale) {
            this.minX[index] = minX;
            this.minY[index] = minY;
            this.minZ[index] = minZ;
            this.maxX[index] = maxX;
            this.maxY[index] = maxY;
            this.maxZ[index] = maxZ;
            this.power[index] = power;
            float x = maxX - minX;
            float y = maxY - minY;
            float z = maxZ - minZ;
            this.softeningDistanceSquared[index] = Math.max(
                    (x * x + y * y + z * z) * softeningScale,
                    MINIMUM_SOFTENING_DISTANCE_SQUARED);
        }

        private Bounds bounds(int index) {
            return new Bounds(
                    this.minX[index],
                    this.minY[index],
                    this.minZ[index],
                    this.maxX[index],
                    this.maxY[index],
                    this.maxZ[index]);
        }
    }

    private static final class Bin {
        private float minX;
        private float minY;
        private float minZ;
        private float maxX;
        private float maxY;
        private float maxZ;
        private float power;
        private int count;

        private void reset() {
            this.minX = Float.POSITIVE_INFINITY;
            this.minY = Float.POSITIVE_INFINITY;
            this.minZ = Float.POSITIVE_INFINITY;
            this.maxX = Float.NEGATIVE_INFINITY;
            this.maxY = Float.NEGATIVE_INFINITY;
            this.maxZ = Float.NEGATIVE_INFINITY;
            this.power = 0.0F;
            this.count = 0;
        }

        private void include(Leaves leaves, int index) {
            this.minX = Math.min(this.minX, leaves.minX[index]);
            this.minY = Math.min(this.minY, leaves.minY[index]);
            this.minZ = Math.min(this.minZ, leaves.minZ[index]);
            this.maxX = Math.max(this.maxX, leaves.maxX[index]);
            this.maxY = Math.max(this.maxY, leaves.maxY[index]);
            this.maxZ = Math.max(this.maxZ, leaves.maxZ[index]);
            this.power += leaves.power[index];
            this.count++;
        }
    }

    private static final class Workspace {
        private final Bin[] bins = new Bin[SAH_BIN_COUNT];
        private final float[] prefixMinX = new float[SAH_BIN_COUNT];
        private final float[] prefixMinY = new float[SAH_BIN_COUNT];
        private final float[] prefixMinZ = new float[SAH_BIN_COUNT];
        private final float[] prefixMaxX = new float[SAH_BIN_COUNT];
        private final float[] prefixMaxY = new float[SAH_BIN_COUNT];
        private final float[] prefixMaxZ = new float[SAH_BIN_COUNT];
        private final float[] prefixPower = new float[SAH_BIN_COUNT];
        private final int[] prefixCount = new int[SAH_BIN_COUNT];
        private final float[] suffixMinX = new float[SAH_BIN_COUNT];
        private final float[] suffixMinY = new float[SAH_BIN_COUNT];
        private final float[] suffixMinZ = new float[SAH_BIN_COUNT];
        private final float[] suffixMaxX = new float[SAH_BIN_COUNT];
        private final float[] suffixMaxY = new float[SAH_BIN_COUNT];
        private final float[] suffixMaxZ = new float[SAH_BIN_COUNT];
        private final float[] suffixPower = new float[SAH_BIN_COUNT];
        private final int[] suffixCount = new int[SAH_BIN_COUNT];
        private float centroidMinX;
        private float centroidMinY;
        private float centroidMinZ;
        private float centroidMaxX;
        private float centroidMaxY;
        private float centroidMaxZ;

        private Workspace() {
            for (int index = 0; index < this.bins.length; index++) {
                this.bins[index] = new Bin();
            }
        }

        private void aggregate() {
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            float power = 0.0F;
            int count = 0;
            for (int index = 0; index < SAH_BIN_COUNT; index++) {
                Bin bin = this.bins[index];
                if (bin.count != 0) {
                    minX = Math.min(minX, bin.minX);
                    minY = Math.min(minY, bin.minY);
                    minZ = Math.min(minZ, bin.minZ);
                    maxX = Math.max(maxX, bin.maxX);
                    maxY = Math.max(maxY, bin.maxY);
                    maxZ = Math.max(maxZ, bin.maxZ);
                    power += bin.power;
                    count += bin.count;
                }
                this.prefixMinX[index] = minX;
                this.prefixMinY[index] = minY;
                this.prefixMinZ[index] = minZ;
                this.prefixMaxX[index] = maxX;
                this.prefixMaxY[index] = maxY;
                this.prefixMaxZ[index] = maxZ;
                this.prefixPower[index] = power;
                this.prefixCount[index] = count;
            }

            minX = Float.POSITIVE_INFINITY;
            minY = Float.POSITIVE_INFINITY;
            minZ = Float.POSITIVE_INFINITY;
            maxX = Float.NEGATIVE_INFINITY;
            maxY = Float.NEGATIVE_INFINITY;
            maxZ = Float.NEGATIVE_INFINITY;
            power = 0.0F;
            count = 0;
            for (int index = SAH_BIN_COUNT - 1; index >= 0; index--) {
                Bin bin = this.bins[index];
                if (bin.count != 0) {
                    minX = Math.min(minX, bin.minX);
                    minY = Math.min(minY, bin.minY);
                    minZ = Math.min(minZ, bin.minZ);
                    maxX = Math.max(maxX, bin.maxX);
                    maxY = Math.max(maxY, bin.maxY);
                    maxZ = Math.max(maxZ, bin.maxZ);
                    power += bin.power;
                    count += bin.count;
                }
                this.suffixMinX[index] = minX;
                this.suffixMinY[index] = minY;
                this.suffixMinZ[index] = minZ;
                this.suffixMaxX[index] = maxX;
                this.suffixMaxY[index] = maxY;
                this.suffixMaxZ[index] = maxZ;
                this.suffixPower[index] = power;
                this.suffixCount[index] = count;
            }
        }

        private void findCentroidBounds(Leaves leaves, int start, int end) {
            this.centroidMinX = Float.POSITIVE_INFINITY;
            this.centroidMinY = Float.POSITIVE_INFINITY;
            this.centroidMinZ = Float.POSITIVE_INFINITY;
            this.centroidMaxX = Float.NEGATIVE_INFINITY;
            this.centroidMaxY = Float.NEGATIVE_INFINITY;
            this.centroidMaxZ = Float.NEGATIVE_INFINITY;
            for (int index = start; index < end; index++) {
                this.centroidMinX = Math.min(this.centroidMinX, leaves.centerX[index]);
                this.centroidMinY = Math.min(this.centroidMinY, leaves.centerY[index]);
                this.centroidMinZ = Math.min(this.centroidMinZ, leaves.centerZ[index]);
                this.centroidMaxX = Math.max(this.centroidMaxX, leaves.centerX[index]);
                this.centroidMaxY = Math.max(this.centroidMaxY, leaves.centerY[index]);
                this.centroidMaxZ = Math.max(this.centroidMaxZ, leaves.centerZ[index]);
            }
        }

        private float centroidMinimum(int axis) {
            return switch (axis) {
                case 0 -> this.centroidMinX;
                case 1 -> this.centroidMinY;
                case 2 -> this.centroidMinZ;
                default -> throw new IndexOutOfBoundsException(axis);
            };
        }

        private float centroidMaximum(int axis) {
            return switch (axis) {
                case 0 -> this.centroidMaxX;
                case 1 -> this.centroidMaxY;
                case 2 -> this.centroidMaxZ;
                default -> throw new IndexOutOfBoundsException(axis);
            };
        }

        private int longestCentroidAxis() {
            float x = this.centroidMaxX - this.centroidMinX;
            float y = this.centroidMaxY - this.centroidMinY;
            float z = this.centroidMaxZ - this.centroidMinZ;
            if (x >= y && x >= z) {
                return 0;
            }
            return y >= z ? 1 : 2;
        }
    }
}
