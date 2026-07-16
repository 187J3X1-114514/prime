package dev.prime.render.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Pure CPU builder for both levels of Prime's light tree.
 *
 * <p>Selection uses the same packed node power and bounds in both directions on the GPU. Bounds
 * and traversal metadata are emitted as separate streams: forward sampling reads both child bounds
 * and one compact child-or-leaf word for each visited node. Reverse MIS reads a separate exact
 * parent stream and derives the sibling from the consecutive-pair invariant instead of
 * reconstructing probabilities from higher-precision CPU state.
 */
final class CpuLightTree {
    static final int NO_INDEX = -1;
    static final float SECTION_SOFTENING_SCALE = 1.0F / 128.0F;
    static final float WORLD_SOFTENING_SCALE = 1.0F / 64.0F;
    static final float MINIMUM_SOFTENING_DISTANCE_SQUARED = 0.25F;
    static final int LEAF_FLAG = Integer.MIN_VALUE;
    static final int INDEX_MASK = Integer.MAX_VALUE;
    private static final int SAH_BIN_COUNT = 16;
    private static final int BOUNDS_WORDS_PER_NODE = 8;
    private static final int FORWARD_WORDS_PER_NODE = 1;
    private static final int REVERSE_WORDS_PER_NODE = 1;
    private static final Comparator<Leaf> X_COMPARATOR =
            Comparator.comparingDouble(Leaf::centerX);
    private static final Comparator<Leaf> Y_COMPARATOR =
            Comparator.comparingDouble(Leaf::centerY);
    private static final Comparator<Leaf> Z_COMPARATOR =
            Comparator.comparingDouble(Leaf::centerZ);

    private CpuLightTree() {
    }

    static Result build(List<Leaf> source, int indexCapacity, float softeningScale) {
        return buildOwned(new ArrayList<>(source), indexCapacity, softeningScale);
    }

    static Result buildOwned(
            ArrayList<Leaf> leaves, int indexCapacity, float softeningScale) {
        if (leaves.isEmpty()) {
            throw new IllegalArgumentException("A light tree requires at least one leaf");
        }
        if (indexCapacity < 0) {
            throw new IllegalArgumentException("Negative light leaf index capacity");
        }
        List<Node> nodes = new ArrayList<>(leaves.size() * 2 - 1);
        int[] leafNodes = new int[indexCapacity];
        Arrays.fill(leafNodes, NO_INDEX);
        Workspace workspace = new Workspace();
        Node rootNode = createNode(leaves, 0, leaves.size(), NO_INDEX, softeningScale);
        nodes.add(rootNode);
        populateNode(
                leaves,
                0,
                leaves.size(),
                0,
                softeningScale,
                nodes,
                leafNodes,
                workspace);
        return new Result(nodes, leafNodes, rootNode.bounds, rootNode.power);
    }

    /**
     * Populates a node whose aggregate data has already been allocated.
     *
     * <p>Both direct children are appended before either subtree is populated. Every sibling pair
     * is therefore consecutive in the packed arrays, so a traversal's mandatory two-child read is
     * spatially coherent without changing the SAH partition or any sampling probability.
     */
    private static void populateNode(
            List<Leaf> leaves,
            int start,
            int end,
            int nodeIndex,
            float softeningScale,
            List<Node> nodes,
            int[] leafNodes,
            Workspace workspace) {
        int count = end - start;
        if (count <= 0) {
            throw new IllegalStateException("Empty light tree range");
        }
        Node node = nodes.get(nodeIndex);
        if (count == 1) {
            Leaf leaf = leaves.get(start);
            if (leaf.index < 0 || leaf.index >= leafNodes.length || leafNodes[leaf.index] != NO_INDEX) {
                throw new IllegalStateException("Invalid or duplicate light leaf index " + leaf.index);
            }
            node.firstChildOrLeaf = leaf.index;
            leafNodes[leaf.index] = nodeIndex;
            return;
        }

        int middle = partition(leaves, start, end, workspace);
        int left = nodes.size();
        nodes.add(createNode(leaves, start, middle, nodeIndex, softeningScale));
        int right = nodes.size();
        nodes.add(createNode(leaves, middle, end, nodeIndex, softeningScale));
        node.firstChildOrLeaf = left;
        node.secondChild = right;
        populateNode(leaves, start, middle, left, softeningScale, nodes, leafNodes, workspace);
        populateNode(leaves, middle, end, right, softeningScale, nodes, leafNodes, workspace);
    }

    private static Node createNode(
            List<Leaf> leaves,
            int start,
            int end,
            int parent,
            float softeningScale) {
        Bounds bounds = boundsOf(leaves, start, end);
        return new Node(
                bounds,
                powerOf(leaves, start, end),
                soften(bounds, softeningScale),
                parent);
    }

    private static int partition(List<Leaf> leaves, int start, int end, Workspace workspace) {
        Bounds centroidBounds = centroidBoundsOf(leaves, start, end);
        float bestCost = Float.POSITIVE_INFINITY;
        int bestAxis = -1;
        int bestSplit = -1;
        for (int axis = 0; axis < 3; axis++) {
            float minimum = centroidBounds.minimum(axis);
            float extent = centroidBounds.maximum(axis) - minimum;
            if (!(extent > 0.0F)) {
                continue;
            }
            Bin[] bins = workspace.bins;
            for (Bin bin : bins) {
                bin.reset();
            }
            for (int index = start; index < end; index++) {
                Leaf leaf = leaves.get(index);
                int binIndex = binIndex(leaf.center(axis), minimum, extent);
                bins[binIndex].include(leaf);
            }
            for (int split = 0; split < SAH_BIN_COUNT - 1; split++) {
                MutableBounds leftBounds = workspace.leftBounds.reset();
                MutableBounds rightBounds = workspace.rightBounds.reset();
                float leftPower = 0.0F;
                float rightPower = 0.0F;
                int leftCount = 0;
                int rightCount = 0;
                for (int bin = 0; bin <= split; bin++) {
                    if (bins[bin].count != 0) {
                        leftBounds.include(bins[bin]);
                        leftPower += bins[bin].power;
                        leftCount += bins[bin].count;
                    }
                }
                for (int bin = split + 1; bin < SAH_BIN_COUNT; bin++) {
                    if (bins[bin].count != 0) {
                        rightBounds.include(bins[bin]);
                        rightPower += bins[bin].power;
                        rightCount += bins[bin].count;
                    }
                }
                if (leftCount == 0 || rightCount == 0) {
                    continue;
                }
                float cost = leftBounds.surfaceArea() * leftPower
                        + rightBounds.surfaceArea() * rightPower;
                if (cost < bestCost) {
                    bestCost = cost;
                    bestAxis = axis;
                    bestSplit = split;
                }
            }
        }

        if (bestAxis >= 0) {
            float minimum = centroidBounds.minimum(bestAxis);
            float extent = centroidBounds.maximum(bestAxis) - minimum;
            int left = start;
            int right = end - 1;
            while (left <= right) {
                if (binIndex(leaves.get(left).center(bestAxis), minimum, extent) <= bestSplit) {
                    left++;
                } else {
                    Leaf swap = leaves.get(left);
                    leaves.set(left, leaves.get(right));
                    leaves.set(right, swap);
                    right--;
                }
            }
            if (left > start && left < end) {
                return left;
            }
        }

        int fallbackAxis = centroidBounds.longestAxis();
        Comparator<Leaf> fallbackComparator = switch (fallbackAxis) {
            case 0 -> X_COMPARATOR;
            case 1 -> Y_COMPARATOR;
            default -> Z_COMPARATOR;
        };
        leaves.subList(start, end).sort(fallbackComparator);
        return start + (end - start) / 2;
    }

    private static int binIndex(float center, float minimum, float extent) {
        float scaled = Math.max(0.0F, Math.min(Math.nextDown(1.0F), (center - minimum) / extent));
        return Math.min((int) (scaled * SAH_BIN_COUNT), SAH_BIN_COUNT - 1);
    }

    private static Bounds boundsOf(List<Leaf> leaves, int start, int end) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int index = start; index < end; index++) {
            Bounds bounds = leaves.get(index).bounds;
            minX = Math.min(minX, bounds.minX);
            minY = Math.min(minY, bounds.minY);
            minZ = Math.min(minZ, bounds.minZ);
            maxX = Math.max(maxX, bounds.maxX);
            maxY = Math.max(maxY, bounds.maxY);
            maxZ = Math.max(maxZ, bounds.maxZ);
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static Bounds centroidBoundsOf(List<Leaf> leaves, int start, int end) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int index = start; index < end; index++) {
            Leaf leaf = leaves.get(index);
            minX = Math.min(minX, leaf.centerX);
            minY = Math.min(minY, leaf.centerY);
            minZ = Math.min(minZ, leaf.centerZ);
            maxX = Math.max(maxX, leaf.centerX);
            maxY = Math.max(maxY, leaf.centerY);
            maxZ = Math.max(maxZ, leaf.centerZ);
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static float powerOf(List<Leaf> leaves, int start, int end) {
        float result = 0.0F;
        for (int index = start; index < end; index++) {
            result += leaves.get(index).power;
        }
        return result;
    }

    private static float soften(Bounds bounds, float scale) {
        return Math.max(bounds.diagonalSquared() * scale, MINIMUM_SOFTENING_DISTANCE_SQUARED);
    }

    static record Leaf(Bounds bounds, float centerX, float centerY, float centerZ, float power, int index) {
        Leaf {
            if (!(power > 0.0F) || !Float.isFinite(power)) {
                throw new IllegalArgumentException("Light power must be finite and positive");
            }
        }

        float center(int axis) {
            return switch (axis) {
                case 0 -> this.centerX;
                case 1 -> this.centerY;
                case 2 -> this.centerZ;
                default -> throw new IndexOutOfBoundsException(axis);
            };
        }
    }

    static record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        static Bounds empty() {
            return new Bounds(
                    Float.POSITIVE_INFINITY,
                    Float.POSITIVE_INFINITY,
                    Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY,
                    Float.NEGATIVE_INFINITY,
                    Float.NEGATIVE_INFINITY);
        }

        Bounds include(float x, float y, float z) {
            return new Bounds(
                    Math.min(this.minX, x),
                    Math.min(this.minY, y),
                    Math.min(this.minZ, z),
                    Math.max(this.maxX, x),
                    Math.max(this.maxY, y),
                    Math.max(this.maxZ, z));
        }

        Bounds union(Bounds other) {
            return new Bounds(
                    Math.min(this.minX, other.minX),
                    Math.min(this.minY, other.minY),
                    Math.min(this.minZ, other.minZ),
                    Math.max(this.maxX, other.maxX),
                    Math.max(this.maxY, other.maxY),
                    Math.max(this.maxZ, other.maxZ));
        }

        Bounds translated(float x, float y, float z) {
            return new Bounds(
                    this.minX + x,
                    this.minY + y,
                    this.minZ + z,
                    this.maxX + x,
                    this.maxY + y,
                    this.maxZ + z);
        }

        float minimum(int axis) {
            return switch (axis) {
                case 0 -> this.minX;
                case 1 -> this.minY;
                case 2 -> this.minZ;
                default -> throw new IndexOutOfBoundsException(axis);
            };
        }

        float maximum(int axis) {
            return switch (axis) {
                case 0 -> this.maxX;
                case 1 -> this.maxY;
                case 2 -> this.maxZ;
                default -> throw new IndexOutOfBoundsException(axis);
            };
        }

        int longestAxis() {
            float x = this.maxX - this.minX;
            float y = this.maxY - this.minY;
            float z = this.maxZ - this.minZ;
            if (x >= y && x >= z) {
                return 0;
            }
            return y >= z ? 1 : 2;
        }

        float surfaceArea() {
            float x = Math.max(this.maxX - this.minX, 0.0F);
            float y = Math.max(this.maxY - this.minY, 0.0F);
            float z = Math.max(this.maxZ - this.minZ, 0.0F);
            return 2.0F * (x * y + y * z + z * x);
        }

        float diagonalSquared() {
            float x = this.maxX - this.minX;
            float y = this.maxY - this.minY;
            float z = this.maxZ - this.minZ;
            return x * x + y * y + z * z;
        }
    }

    static final class Result {
        private final List<Node> nodes;
        private final int[] leafNodes;
        private final Bounds bounds;
        private final float power;

        private Result(List<Node> nodes, int[] leafNodes, Bounds bounds, float power) {
            this.nodes = nodes;
            this.leafNodes = leafNodes;
            this.bounds = bounds;
            this.power = power;
        }

        int[] packNodeBounds() {
            int[] result = new int[this.nodes.size() * BOUNDS_WORDS_PER_NODE];
            int cursor = 0;
            for (Node node : this.nodes) {
                result[cursor++] = Float.floatToRawIntBits(node.bounds.minX);
                result[cursor++] = Float.floatToRawIntBits(node.bounds.minY);
                result[cursor++] = Float.floatToRawIntBits(node.bounds.minZ);
                result[cursor++] = Float.floatToRawIntBits(node.power);
                result[cursor++] = Float.floatToRawIntBits(node.bounds.maxX);
                result[cursor++] = Float.floatToRawIntBits(node.bounds.maxY);
                result[cursor++] = Float.floatToRawIntBits(node.bounds.maxZ);
                result[cursor++] = Float.floatToRawIntBits(node.softeningDistanceSquared);
            }
            return result;
        }

        int[] packNodeForward() {
            int[] result = new int[this.nodes.size() * FORWARD_WORDS_PER_NODE];
            int cursor = 0;
            for (Node node : this.nodes) {
                if (node.firstChildOrLeaf < 0) {
                    throw new IllegalStateException("Light tree node was not populated");
                }
                if (node.secondChild == NO_INDEX) {
                    result[cursor++] = node.firstChildOrLeaf | LEAF_FLAG;
                } else {
                    if (node.secondChild != node.firstChildOrLeaf + 1) {
                        throw new IllegalStateException("Light tree siblings must be consecutive");
                    }
                    result[cursor++] = node.firstChildOrLeaf;
                }
            }
            return result;
        }

        int[] packNodeReverse() {
            int[] result = new int[this.nodes.size() * REVERSE_WORDS_PER_NODE];
            int cursor = 0;
            for (Node node : this.nodes) {
                result[cursor++] = node.parent;
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
            for (Node node : this.nodes) {
                target[boundsCursor++] = Float.floatToRawIntBits(node.bounds.minX);
                target[boundsCursor++] = Float.floatToRawIntBits(node.bounds.minY);
                target[boundsCursor++] = Float.floatToRawIntBits(node.bounds.minZ);
                target[boundsCursor++] = Float.floatToRawIntBits(node.power);
                target[boundsCursor++] = Float.floatToRawIntBits(node.bounds.maxX);
                target[boundsCursor++] = Float.floatToRawIntBits(node.bounds.maxY);
                target[boundsCursor++] = Float.floatToRawIntBits(node.bounds.maxZ);
                target[boundsCursor++] = Float.floatToRawIntBits(node.softeningDistanceSquared);
                if (node.firstChildOrLeaf < 0) {
                    throw new IllegalStateException("Light tree node was not populated");
                }
                if (node.secondChild == NO_INDEX) {
                    target[forwardCursor++] = node.firstChildOrLeaf | LEAF_FLAG;
                } else {
                    if (node.secondChild != node.firstChildOrLeaf + 1) {
                        throw new IllegalStateException("Light tree siblings must be consecutive");
                    }
                    target[forwardCursor++] = node.firstChildOrLeaf;
                }
                target[reverseCursor++] = node.parent;
            }
        }

        int leafNode(int leafIndex) {
            return this.leafNodes[leafIndex];
        }

        int[] leafNodes() {
            return this.leafNodes;
        }

        Bounds bounds() {
            return this.bounds;
        }

        float power() {
            return this.power;
        }

        int nodeCount() {
            return this.nodes.size();
        }
    }

    private static final class Node {
        private final Bounds bounds;
        private final float power;
        private final float softeningDistanceSquared;
        private final int parent;
        private int firstChildOrLeaf = NO_INDEX;
        private int secondChild = NO_INDEX;

        private Node(Bounds bounds, float power, float softeningDistanceSquared, int parent) {
            this.bounds = bounds;
            this.power = power;
            this.softeningDistanceSquared = softeningDistanceSquared;
            this.parent = parent;
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

        private void include(Leaf leaf) {
            this.minX = Math.min(this.minX, leaf.bounds.minX);
            this.minY = Math.min(this.minY, leaf.bounds.minY);
            this.minZ = Math.min(this.minZ, leaf.bounds.minZ);
            this.maxX = Math.max(this.maxX, leaf.bounds.maxX);
            this.maxY = Math.max(this.maxY, leaf.bounds.maxY);
            this.maxZ = Math.max(this.maxZ, leaf.bounds.maxZ);
            this.power += leaf.power;
            this.count++;
        }
    }

    private static final class MutableBounds {
        private float minX;
        private float minY;
        private float minZ;
        private float maxX;
        private float maxY;
        private float maxZ;

        private MutableBounds reset() {
            this.minX = Float.POSITIVE_INFINITY;
            this.minY = Float.POSITIVE_INFINITY;
            this.minZ = Float.POSITIVE_INFINITY;
            this.maxX = Float.NEGATIVE_INFINITY;
            this.maxY = Float.NEGATIVE_INFINITY;
            this.maxZ = Float.NEGATIVE_INFINITY;
            return this;
        }

        private void include(Bin bin) {
            this.minX = Math.min(this.minX, bin.minX);
            this.minY = Math.min(this.minY, bin.minY);
            this.minZ = Math.min(this.minZ, bin.minZ);
            this.maxX = Math.max(this.maxX, bin.maxX);
            this.maxY = Math.max(this.maxY, bin.maxY);
            this.maxZ = Math.max(this.maxZ, bin.maxZ);
        }

        private float surfaceArea() {
            float x = Math.max(this.maxX - this.minX, 0.0F);
            float y = Math.max(this.maxY - this.minY, 0.0F);
            float z = Math.max(this.maxZ - this.minZ, 0.0F);
            return 2.0F * (x * y + y * z + z * x);
        }
    }

    private static final class Workspace {
        private final Bin[] bins = new Bin[SAH_BIN_COUNT];
        private final MutableBounds leftBounds = new MutableBounds();
        private final MutableBounds rightBounds = new MutableBounds();

        private Workspace() {
            for (int index = 0; index < this.bins.length; index++) {
                this.bins[index] = new Bin();
            }
        }
    }
}
