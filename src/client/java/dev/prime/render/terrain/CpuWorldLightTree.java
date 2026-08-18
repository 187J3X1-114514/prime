package dev.prime.render.terrain;

import dev.prime.render.shader.ShaderAbi;
import java.util.Arrays;

/** Pure CPU builder for the packed top-level world light tree and flat section proposal. */
public final class CpuWorldLightTree {
    private CpuWorldLightTree() {}

    public static Result build(WorldLightTreeInput input) {
        int lightCount = lightCount(input);
        if (lightCount == 0) {
            return Result.empty(input.clusterCount());
        }

        CpuLightTree.Leaves leaves = new CpuLightTree.Leaves(lightCount);
        WorldSummaries summaries = new WorldSummaries(lightCount);
        for (int clusterIndex = 0; clusterIndex < input.clusterCount(); clusterIndex++) {
            CompiledClusterLights.Summary lights = input.lights(clusterIndex);
            if (lights.isEmpty()) {
                continue;
            }
            CpuLightTree.Bounds bounds = lights.bounds();
            float translateX = (float) (((long) input.clusterX(clusterIndex) << 4)
                    - input.originX());
            float translateY = (float) (((long) input.clusterY(clusterIndex) << 4)
                    - input.originY());
            float translateZ = (float) (((long) input.clusterZ(clusterIndex) << 4)
                    - input.originZ());
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
            summaries.add(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    lights.power(),
                    lights.packedDirection(),
                    clusterIndex);
        }

        CpuLightTree.Result tree = CpuLightTree.buildOwned(leaves, input.clusterCount());
        Result result = Result.forTree(tree, summaries, input.clusterCount());
        for (int clusterIndex = 0; clusterIndex < input.clusterCount(); clusterIndex++) {
            result.setLightPath(clusterIndex, tree.leafPath(clusterIndex));
        }
        result.pack(tree, summaries);
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

    public static final class Result {
        private final int[] packedWords;
        private final int nodeWordOffset;
        private final int nodeWordCount;
        private final int leafWordOffset;
        private final int leafWordCount;
        private final int entryWordOffset;
        private final int summaryWordOffset;
        private final int aliasWordOffset;
        private final int summaryCount;
        private final int[] lightPaths;

        private Result(
                int[] packedWords,
                int nodeWordOffset,
                int nodeWordCount,
                int leafWordOffset,
                int leafWordCount,
                int entryWordOffset,
                int summaryWordOffset,
                int aliasWordOffset,
                int summaryCount,
                int clusterCount) {
            this.packedWords = packedWords;
            this.nodeWordOffset = nodeWordOffset;
            this.nodeWordCount = nodeWordCount;
            this.leafWordOffset = leafWordOffset;
            this.leafWordCount = leafWordCount;
            this.entryWordOffset = entryWordOffset;
            this.summaryWordOffset = summaryWordOffset;
            this.aliasWordOffset = aliasWordOffset;
            this.summaryCount = summaryCount;
            this.lightPaths = new int[clusterCount];
            Arrays.fill(this.lightPaths, CpuLightTree.NO_INDEX);
        }

        public static Result empty(int clusterCount) {
            if (clusterCount < 0) {
                throw new IllegalArgumentException("Negative world light cluster count");
            }
            return new Result(new int[0], 0, 0, 0, 0, 0, 0, 0, 0, clusterCount);
        }

        private static Result forTree(
                CpuLightTree.Result tree,
                WorldSummaries summaries,
                int clusterCount) {
            int headerWords = ShaderAbi.WORLD_LIGHT_HEADER_SIZE / Integer.BYTES;
            int nodeWordCount = tree.nodeCount()
                    * (ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES);
            int leafWordCount = tree.clusterCount()
                    * (ShaderAbi.LIGHT_LEAF_SIZE / Integer.BYTES);
            int entryWordCount = tree.entryCount()
                    * (ShaderAbi.LIGHT_LEAF_ENTRY_SIZE / Integer.BYTES);
            int summaryWords = ShaderAbi.WORLD_LIGHT_SUMMARY_SIZE / Integer.BYTES;
            int aliasWords = ShaderAbi.LIGHT_ALIAS_ENTRY_SIZE / Integer.BYTES;
            int nodeWordOffset = headerWords;
            int leafWordOffset = nodeWordOffset + nodeWordCount;
            int entryWordOffset = leafWordOffset + leafWordCount;
            int summaryWordOffset = Math.toIntExact(alignUp(
                            (long) (entryWordOffset + entryWordCount) * Integer.BYTES,
                            16L)
                    / Integer.BYTES);
            int aliasWordOffset = summaryWordOffset + summaries.size * summaryWords;
            int wordCount = aliasWordOffset + summaries.size * aliasWords;
            Result result = new Result(
                    new int[wordCount],
                    nodeWordOffset,
                    nodeWordCount,
                    leafWordOffset,
                    leafWordCount,
                    entryWordOffset,
                    summaryWordOffset,
                    aliasWordOffset,
                    summaries.size,
                    clusterCount);
            putLong(result.packedWords, 0, (long) nodeWordOffset * Integer.BYTES);
            putLong(result.packedWords, 2, (long) leafWordOffset * Integer.BYTES);
            putLong(result.packedWords, 4, (long) entryWordOffset * Integer.BYTES);
            putLong(result.packedWords, 6, (long) summaryWordOffset * Integer.BYTES);
            putLong(result.packedWords, 8, (long) aliasWordOffset * Integer.BYTES);
            result.packedWords[10] = summaries.size;
            result.packedWords[11] = Float.floatToRawIntBits(tree.power());
            return result;
        }

        private void setLightPath(int clusterIndex, int lightPath) {
            this.lightPaths[clusterIndex] = lightPath;
        }

        private void pack(CpuLightTree.Result tree, WorldSummaries summaries) {
            tree.packInto(
                    this.packedWords,
                    this.nodeWordOffset,
                    this.leafWordOffset,
                    this.entryWordOffset);
            PowerAliasTable alias = summaries.aliasTable();
            int summaryWords = ShaderAbi.WORLD_LIGHT_SUMMARY_SIZE / Integer.BYTES;
            for (int index = 0; index < summaries.size; index++) {
                int cursor = this.summaryWordOffset + index * summaryWords;
                putFloat(this.packedWords, cursor, summaries.minX[index]);
                putFloat(this.packedWords, cursor + 1, summaries.minY[index]);
                putFloat(this.packedWords, cursor + 2, summaries.minZ[index]);
                putFloat(this.packedWords, cursor + 3, summaries.power[index]);
                putFloat(this.packedWords, cursor + 4, summaries.maxX[index]);
                putFloat(this.packedWords, cursor + 5, summaries.maxY[index]);
                putFloat(this.packedWords, cursor + 6, summaries.maxZ[index]);
                putFloat(this.packedWords, cursor + 7, alias.probabilityMass(index));
                this.packedWords[cursor + 8] = summaries.packedDirection[index];
                this.packedWords[cursor + 9] = summaries.sectionIndex[index];
                this.packedWords[cursor + 10] = 0;
                this.packedWords[cursor + 11] = 0;
            }
            alias.packInto(this.packedWords, this.aliasWordOffset);
        }

        public boolean isEmpty() {
            return this.nodeWordCount == 0;
        }

        public int[] pack() {
            return this.packedWords;
        }

        public long nodeByteOffset() {
            return (long) this.nodeWordOffset * Integer.BYTES;
        }

        public long leafByteOffset() {
            return (long) this.leafWordOffset * Integer.BYTES;
        }

        public long entryByteOffset() {
            return (long) this.entryWordOffset * Integer.BYTES;
        }

        public long summaryByteOffset() {
            return (long) this.summaryWordOffset * Integer.BYTES;
        }

        public long aliasByteOffset() {
            return (long) this.aliasWordOffset * Integer.BYTES;
        }

        public int nodeCount() {
            return this.nodeWordCount / (ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES);
        }

        public int leafCount() {
            return this.leafWordCount / (ShaderAbi.LIGHT_LEAF_SIZE / Integer.BYTES);
        }

        public int summaryCount() {
            return this.summaryCount;
        }

        public int lightPath(int clusterIndex) {
            if (clusterIndex < 0 || clusterIndex >= this.lightPaths.length) {
                throw new IndexOutOfBoundsException(clusterIndex);
            }
            return this.lightPaths[clusterIndex];
        }
    }

    private static final class WorldSummaries {
        private final float[] minX;
        private final float[] minY;
        private final float[] minZ;
        private final float[] maxX;
        private final float[] maxY;
        private final float[] maxZ;
        private final float[] power;
        private final int[] packedDirection;
        private final int[] sectionIndex;
        private int size;

        private WorldSummaries(int capacity) {
            this.minX = new float[capacity];
            this.minY = new float[capacity];
            this.minZ = new float[capacity];
            this.maxX = new float[capacity];
            this.maxY = new float[capacity];
            this.maxZ = new float[capacity];
            this.power = new float[capacity];
            this.packedDirection = new int[capacity];
            this.sectionIndex = new int[capacity];
        }

        private void add(
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                float power,
                int packedDirection,
                int sectionIndex) {
            int index = this.size++;
            this.minX[index] = minX;
            this.minY[index] = minY;
            this.minZ[index] = minZ;
            this.maxX[index] = maxX;
            this.maxY[index] = maxY;
            this.maxZ[index] = maxZ;
            this.power[index] = power;
            this.packedDirection[index] = packedDirection;
            this.sectionIndex[index] = sectionIndex;
        }

        private PowerAliasTable aliasTable() {
            return PowerAliasTable.build(Arrays.copyOf(this.power, this.size));
        }
    }

    private static void putLong(int[] target, int wordOffset, long value) {
        target[wordOffset] = (int) value;
        target[wordOffset + 1] = (int) (value >>> 32);
    }

    private static void putFloat(int[] target, int wordOffset, float value) {
        target[wordOffset] = Float.floatToRawIntBits(value);
    }

    private static long alignUp(long value, long alignment) {
        return Math.addExact(value, alignment - 1L) / alignment * alignment;
    }
}
