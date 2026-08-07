package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpritePixelView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Logically immutable CPU build input for alpha-cutout geometry's mixed-format Vulkan opacity
 * micromap.
 *
 * <p>Builders transfer ownership of the packed arrays. Public array access is a borrowed read-only
 * view used by serialization and Vulkan upload; mutating it violates the scene-value contract.
 */
public final class OpacityMicromapData {
    public static final int SUBDIVISION_LEVEL = 4;
    public static final int MAX_TEXTURE_SUBDIVISION_LEVEL = 8;
    public static final int MAX_SUBDIVISION_LEVEL = MAX_TEXTURE_SUBDIVISION_LEVEL + 2;
    public static final int MICRO_TRIANGLE_COUNT = 1 << (2 * SUBDIVISION_LEVEL);
    // Stable VK_EXT_opacity_micromap wire values consumed only at the Vulkan adapter.
    public static final int TWO_STATE_FORMAT = 1;
    public static final int FOUR_STATE_FORMAT = 2;
    public static final int TWO_STATE_BYTES_PER_BLOCK =
            (MICRO_TRIANGLE_COUNT + Byte.SIZE - 1) / Byte.SIZE;
    public static final int FOUR_STATE_BYTES_PER_BLOCK =
            (MICRO_TRIANGLE_COUNT * 2 + Byte.SIZE - 1) / Byte.SIZE;

    private static final int STATE_TRANSPARENT = 0;
    private static final int STATE_OPAQUE = 1;
    private static final int STATE_UNKNOWN_OPAQUE = 3;
    private static final int SPECIAL_FULLY_TRANSPARENT = -1;
    private static final int SPECIAL_FULLY_OPAQUE = -2;
    private static final int SPECIAL_FULLY_UNKNOWN_TRANSPARENT = -3;
    private static final int SPECIAL_FULLY_UNKNOWN_OPAQUE = -4;
    private static final int MICROMAP_TRIANGLE_DESCRIPTOR_BYTES = 8;
    private static final int MISALIGNED_UV_REFINEMENT_LEVELS = 2;
    private static final float TEXEL_ALIGNMENT_EPSILON = 1.0E-2F;

    public static final OpacityMicromapData EMPTY =
            new OpacityMicromapData(
                    new byte[0], new int[0], new int[0], new int[0], new int[0]);

    private final byte[] blocks;
    private final int[] blockOffsets;
    private final int[] blockFormats;
    private final int[] blockSubdivisionLevels;
    private final int[] triangleIndices;

    private OpacityMicromapData(
            byte[] blocks,
            int[] blockOffsets,
            int[] blockFormats,
            int[] blockSubdivisionLevels,
            int[] triangleIndices) {
        if (blockOffsets.length != blockFormats.length
                || blockOffsets.length != blockSubdivisionLevels.length) {
            throw new IllegalArgumentException("Opacity micromap block metadata is inconsistent");
        }
        int expectedOffset = 0;
        for (int index = 0; index < blockFormats.length; index++) {
            if (blockOffsets[index] != expectedOffset) {
                throw new IllegalArgumentException("Opacity micromap blocks are not tightly packed");
            }
            expectedOffset = Math.addExact(
                    expectedOffset,
                    blockByteSize(blockFormats[index], blockSubdivisionLevels[index]));
        }
        if (expectedOffset != blocks.length) {
            throw new IllegalArgumentException("Opacity micromap byte storage is inconsistent");
        }
        this.blocks = blocks;
        this.blockOffsets = blockOffsets;
        this.blockFormats = blockFormats;
        this.blockSubdivisionLevels = blockSubdivisionLevels;
        this.triangleIndices = triangleIndices;
    }

    static OpacityMicromapData fromEncoded(
            byte[] blocks,
            int[] blockOffsets,
            int[] blockFormats,
            int[] blockSubdivisionLevels,
            int[] triangleIndices) {
        requireValidTriangleIndices(triangleIndices, blockFormats.length);
        return new OpacityMicromapData(
                blocks.clone(),
                blockOffsets.clone(),
                blockFormats.clone(),
                blockSubdivisionLevels.clone(),
                triangleIndices.clone());
    }

    /** Borrowed read-only packed block storage. */
    public byte[] blocks() {
        return this.blocks;
    }

    /** Borrowed read-only triangle-to-block mapping. */
    public int[] triangleIndices() {
        return this.triangleIndices;
    }

    /** Borrowed read-only packed block offsets. */
    public int[] blockOffsets() {
        return this.blockOffsets;
    }

    /** Borrowed read-only packed block formats. */
    public int[] blockFormats() {
        return this.blockFormats;
    }

    /** Borrowed read-only packed block subdivision levels. */
    public int[] blockSubdivisionLevels() {
        return this.blockSubdivisionLevels;
    }

    public int triangleCount() {
        return this.triangleIndices.length;
    }

    public int blockStorageBytes() {
        return this.blocks.length;
    }

    /** Revalidates borrowed storage before it crosses the Vulkan boundary. */
    public void requireValidTriangleIndices() {
        requireValidTriangleIndices(this.triangleIndices, this.blockFormats.length);
    }

    public int blockCount() {
        return this.blockFormats.length;
    }

    public int blockCount(int format) {
        int count = 0;
        for (int blockFormat : this.blockFormats) {
            count += blockFormat == format ? 1 : 0;
        }
        return count;
    }

    public int blockCount(int format, int subdivisionLevel) {
        int count = 0;
        for (int index = 0; index < this.blockFormats.length; index++) {
            count += this.blockFormats[index] == format
                    && this.blockSubdivisionLevels[index] == subdivisionLevel
                    ? 1
                    : 0;
        }
        return count;
    }

    public static int blockByteSize(int format) {
        return blockByteSize(format, SUBDIVISION_LEVEL);
    }

    public static int blockByteSize(int format, int subdivisionLevel) {
        int microTriangleCount = microTriangleCount(subdivisionLevel);
        long bits = switch (format) {
            case TWO_STATE_FORMAT -> microTriangleCount;
            case FOUR_STATE_FORMAT -> (long) microTriangleCount * 2L;
            default -> throw new IllegalArgumentException(
                    "Unsupported opacity micromap format: " + format);
        };
        return Math.toIntExact((bits + Byte.SIZE - 1L) / Byte.SIZE);
    }

    public static int microTriangleCount(int subdivisionLevel) {
        if (subdivisionLevel < 0 || subdivisionLevel > 15) {
            throw new IllegalArgumentException(
                    "Opacity micromap subdivision level is outside its packed ABI");
        }
        return 1 << (2 * subdivisionLevel);
    }

    public boolean isEmpty() {
        return this.triangleIndices.length == 0;
    }

    public long byteSize() {
        return (long) this.blocks.length
                + (long) this.blockCount() * MICROMAP_TRIANGLE_DESCRIPTOR_BYTES
                + (long) this.triangleIndices.length * Integer.BYTES;
    }

    public static OpacityMicromapData fullyUnknown(int triangleCount) {
        if (triangleCount < 0) {
            throw new IllegalArgumentException("Triangle count must not be negative");
        }
        if (triangleCount == 0) {
            return EMPTY;
        }
        int[] indices = new int[triangleCount];
        Arrays.fill(
                indices,
                SPECIAL_FULLY_UNKNOWN_OPAQUE);
        return new OpacityMicromapData(
                new byte[0], new int[0], new int[0], new int[0], indices);
    }

    private static void requireValidTriangleIndices(
            int[] triangleIndices, int blockCount) {
        for (int index : triangleIndices) {
            if (index >= blockCount
                    || index < SPECIAL_FULLY_UNKNOWN_OPAQUE) {
                throw new IllegalArgumentException(
                        "Opacity micromap triangle references an invalid block: " + index);
            }
        }
    }

    /** Per-mesh builder. Cluster merging reuses the same content interning path. */
    public static final class Builder {
        private final ArrayList<BakedBlock> blocks = new ArrayList<>();
        private final Map<BlockKey, Integer> blockIndices = new HashMap<>();
        private final Map<BakeKey, Integer> bakedTriangles = new HashMap<>();
        private final Map<ConstantBakeKey, Integer> bakedConstantTriangles = new HashMap<>();
        private final Map<RepeatedBakeKey, Integer> bakedRepeatedTriangles = new HashMap<>();
        private final Map<CapturedSprite, SpriteAlphaFrames> alphaFrames = new HashMap<>();
        private final int maxTwoStateSubdivisionLevel;
        private final int maxFourStateSubdivisionLevel;
        private int[] triangleIndices = new int[32];
        private int triangleCount;

        public Builder() {
            this(MAX_SUBDIVISION_LEVEL, MAX_SUBDIVISION_LEVEL);
        }

        public Builder(int maxSubdivisionLevel) {
            this(maxSubdivisionLevel, maxSubdivisionLevel);
        }

        public Builder(
                int maxTwoStateSubdivisionLevel,
                int maxFourStateSubdivisionLevel) {
            if (maxTwoStateSubdivisionLevel < 0
                    || maxFourStateSubdivisionLevel < 0) {
                throw new IllegalArgumentException(
                        "Opacity micromap subdivision limit must be nonnegative");
            }
            this.maxTwoStateSubdivisionLevel = Math.min(
                    maxTwoStateSubdivisionLevel, MAX_SUBDIVISION_LEVEL);
            this.maxFourStateSubdivisionLevel = Math.min(
                    maxFourStateSubdivisionLevel, MAX_SUBDIVISION_LEVEL);
        }

        public void addTriangle(
                CapturedSprite sprite,
                int packedUv0,
                int packedUv1,
                int packedUv2) {
            BakeKey key = new BakeKey(sprite, packedUv0, packedUv1, packedUv2);
            Integer cached = this.bakedTriangles.get(key);
            if (cached != null) {
                this.addIndex(cached);
                return;
            }
            SpriteAlphaFrames frames =
                    this.alphaFrames.computeIfAbsent(sprite, SpriteAlphaFrames::create);
            if (frames == null) {
                int index = SPECIAL_FULLY_UNKNOWN_OPAQUE;
                this.bakedTriangles.put(key, index);
                this.addIndex(index);
                return;
            }
            int index = this.intern(bake(
                    frames,
                    packedUv0,
                    packedUv1,
                    packedUv2,
                    frames.staticPowerOfTwo()
                            ? this.maxTwoStateSubdivisionLevel
                            : this.maxFourStateSubdivisionLevel));
            this.bakedTriangles.put(key, index);
            this.addIndex(index);
        }

        public void addConstantTriangle(
                CapturedSprite sprite, float atlasU, float atlasV) {
            ConstantBakeKey key = new ConstantBakeKey(
                    sprite,
                    Float.floatToRawIntBits(atlasU),
                    Float.floatToRawIntBits(atlasV));
            Integer cached = this.bakedConstantTriangles.get(key);
            if (cached != null) {
                this.addIndex(cached);
                return;
            }
            SpriteAlphaFrames frames =
                    this.alphaFrames.computeIfAbsent(sprite, SpriteAlphaFrames::create);
            int index = frames == null
                    ? SPECIAL_FULLY_UNKNOWN_OPAQUE
                    : this.intern(bakeConstant(frames, atlasU, atlasV));
            this.bakedConstantTriangles.put(key, index);
            this.addIndex(index);
        }

        public void addRepeatedTriangle(
                CapturedSprite sprite,
                int packedUv0,
                int packedUv1,
                int packedUv2,
                int size,
                float projectedU0,
                float projectedV0,
                float projectedU1,
                float projectedV1,
                float projectedU2,
                float projectedV2) {
            if (size <= 0 || size > 4 || (size & size - 1) != 0) {
                throw new IllegalArgumentException(
                        "Cutout macro-face size must be one, two, or four blocks");
            }
            RepeatedBakeKey key = new RepeatedBakeKey(
                    sprite,
                    packedUv0,
                    packedUv1,
                    packedUv2,
                    size,
                    projectedU0,
                    projectedV0,
                    projectedU1,
                    projectedV1,
                    projectedU2,
                    projectedV2);
            Integer cached = this.bakedRepeatedTriangles.get(key);
            if (cached != null) {
                this.addIndex(cached);
                return;
            }
            SpriteAlphaFrames frames =
                    this.alphaFrames.computeIfAbsent(sprite, SpriteAlphaFrames::create);
            int index = frames == null
                    ? SPECIAL_FULLY_UNKNOWN_OPAQUE
                    : this.intern(bakeRepeated(
                            frames,
                            packedUv0,
                            packedUv1,
                            packedUv2,
                            frames.staticPowerOfTwo()
                                    ? this.maxTwoStateSubdivisionLevel
                                    : this.maxFourStateSubdivisionLevel,
                            size,
                            projectedU0,
                            projectedV0,
                            projectedU1,
                            projectedV1,
                            projectedU2,
                            projectedV2));
            this.bakedRepeatedTriangles.put(key, index);
            this.addIndex(index);
        }

        public void addFullyUnknownTriangle() {
            this.addIndex(SPECIAL_FULLY_UNKNOWN_OPAQUE);
        }

        public void append(OpacityMicromapData data) {
            int[] remappedBlocks = new int[data.blockCount()];
            Arrays.fill(remappedBlocks, Integer.MIN_VALUE);
            for (int sourceIndex : data.triangleIndices) {
                if (sourceIndex < 0) {
                    this.addIndex(sourceIndex);
                    continue;
                }
                int destinationIndex = remappedBlocks[sourceIndex];
                if (destinationIndex == Integer.MIN_VALUE) {
                    int offset = data.blockOffsets[sourceIndex];
                    int format = data.blockFormats[sourceIndex];
                    int subdivisionLevel = data.blockSubdivisionLevels[sourceIndex];
                    BakedBlock block = new BakedBlock(
                            format,
                            subdivisionLevel,
                            Arrays.copyOfRange(
                                    data.blocks,
                                    offset,
                                    offset + blockByteSize(format, subdivisionLevel)));
                    destinationIndex = this.intern(block);
                    remappedBlocks[sourceIndex] = destinationIndex;
                }
                this.addIndex(destinationIndex);
            }
        }

        public OpacityMicromapData build() {
            if (this.triangleCount == 0) {
                return EMPTY;
            }
            return pack(
                    this.blocks.toArray(BakedBlock[]::new),
                    Arrays.copyOf(this.triangleIndices, this.triangleCount));
        }

        private int intern(BakedBlock block) {
            boolean allOpaque = true;
            boolean allTransparent = true;
            boolean allUnknownOpaque = block.format == FOUR_STATE_FORMAT;
            for (int index = 0;
                    index < microTriangleCount(block.subdivisionLevel);
                    index++) {
                int state = block.state(index);
                allOpaque &= state == STATE_OPAQUE;
                allTransparent &= state == STATE_TRANSPARENT;
                allUnknownOpaque &= state == STATE_UNKNOWN_OPAQUE;
            }
            if (allOpaque) {
                return SPECIAL_FULLY_OPAQUE;
            }
            if (allTransparent) {
                return SPECIAL_FULLY_TRANSPARENT;
            }
            if (allUnknownOpaque) {
                return SPECIAL_FULLY_UNKNOWN_OPAQUE;
            }
            BlockKey key = new BlockKey(
                    block.format, block.subdivisionLevel, block.states);
            Integer existing = this.blockIndices.get(key);
            if (existing != null) {
                return existing;
            }
            int index = this.blocks.size();
            this.blocks.add(block);
            this.blockIndices.put(key, index);
            return index;
        }

        private void addIndex(int index) {
            if (this.triangleCount == this.triangleIndices.length) {
                this.triangleIndices = Arrays.copyOf(
                        this.triangleIndices, Math.multiplyExact(this.triangleIndices.length, 2));
            }
            this.triangleIndices[this.triangleCount++] = index;
        }
    }

    static OpacityMicromapData pack(BakedBlock[] blocks, int[] triangleIndices) {
        int packedSize = 0;
        for (BakedBlock block : blocks) {
            packedSize = Math.addExact(packedSize, block.states.length);
        }
        byte[] packedBlocks = new byte[packedSize];
        int[] blockOffsets = new int[blocks.length];
        int[] blockFormats = new int[blocks.length];
        int[] blockSubdivisionLevels = new int[blocks.length];
        int destination = 0;
        for (int index = 0; index < blocks.length; index++) {
            BakedBlock block = blocks[index];
            blockOffsets[index] = destination;
            blockFormats[index] = block.format;
            blockSubdivisionLevels[index] = block.subdivisionLevel;
            System.arraycopy(
                    block.states, 0, packedBlocks, destination, block.states.length);
            destination += block.states.length;
        }
        return new OpacityMicromapData(
                packedBlocks,
                blockOffsets,
                blockFormats,
                blockSubdivisionLevels,
                triangleIndices);
    }

    private static BakedBlock bake(
            SpriteAlphaFrames frames,
            int packedUv0,
            int packedUv1,
            int packedUv2,
            int maxSubdivisionLevel) {
        float u0 = frames.localU(unpackHalf(packedUv0, false));
        float v0 = frames.localV(unpackHalf(packedUv0, true));
        float u1 = frames.localU(unpackHalf(packedUv1, false));
        float v1 = frames.localV(unpackHalf(packedUv1, true));
        float u2 = frames.localU(unpackHalf(packedUv2, false));
        float v2 = frames.localV(unpackHalf(packedUv2, true));
        return bakeCoverage(
                u0,
                v0,
                u1,
                v1,
                u2,
                v2,
                frames.subdivisionLevel(
                        u0, v0, u1, v1, u2, v2, maxSubdivisionLevel, 0),
                frames.frameCount(),
                frames.staticPowerOfTwo(),
                frames);
    }

    private static BakedBlock bakeConstant(
            SpriteAlphaFrames frames, float atlasU, float atlasV) {
        float u = frames.localU(atlasU);
        float v = frames.localV(atlasV);
        return bakeCoverage(
                u, v, u, v, u, v, frames.frameCount(), frames);
    }

    private static BakedBlock bakeRepeated(
            SpriteAlphaFrames frames,
            int packedUv0,
            int packedUv1,
            int packedUv2,
            int maxSubdivisionLevel,
            int size,
            float projectedU0,
            float projectedV0,
            float projectedU1,
            float projectedV1,
            float projectedU2,
            float projectedV2) {
        float u0 = frames.localU(unpackHalf(packedUv0, false));
        float v0 = frames.localV(unpackHalf(packedUv0, true));
        float u1 = frames.localU(unpackHalf(packedUv1, false));
        float v1 = frames.localV(unpackHalf(packedUv1, true));
        float u2 = frames.localU(unpackHalf(packedUv2, false));
        float v2 = frames.localV(unpackHalf(packedUv2, true));
        return bakeMapped(
                frames.subdivisionLevel(
                        u0,
                        v0,
                        u1,
                        v1,
                        u2,
                        v2,
                        maxSubdivisionLevel,
                        Integer.numberOfTrailingZeros(size)),
                frames.frameCount(),
                frames.staticPowerOfTwo(),
                (frame, barycentric) -> {
                    float projectedU = interpolate(
                            projectedU0, projectedU1, projectedU2, barycentric);
                    float projectedV = interpolate(
                            projectedV0, projectedV1, projectedV2, barycentric);
                    float repeatedU = repeat(projectedU);
                    float repeatedV = repeat(projectedV);
                    float textureU = u0 + repeatedU * (u1 - u0) + repeatedV * (u2 - u0);
                    float textureV = v0 + repeatedU * (v1 - v0) + repeatedV * (v2 - v0);
                    return frames.opaque(frame, textureU, textureV);
                });
    }

    static BakedBlock bakeCoverage(
            float u0,
            float v0,
            float u1,
            float v1,
            float u2,
            float v2,
            int frameCount,
            AlphaSampler alphaSampler) {
        return bakeCoverage(
                u0,
                v0,
                u1,
                v1,
                u2,
                v2,
                SUBDIVISION_LEVEL,
                frameCount,
                alphaSampler);
    }

    static BakedBlock bakeCoverage(
            float u0,
            float v0,
            float u1,
            float v1,
            float u2,
            float v2,
            int subdivisionLevel,
            int frameCount,
            AlphaSampler alphaSampler) {
        return bakeCoverage(
                u0,
                v0,
                u1,
                v1,
                u2,
                v2,
                subdivisionLevel,
                frameCount,
                false,
                alphaSampler);
    }

    private static BakedBlock bakeCoverage(
            float u0,
            float v0,
            float u1,
            float v1,
            float u2,
            float v2,
            int subdivisionLevel,
            int frameCount,
            boolean forceTwoState,
            AlphaSampler alphaSampler) {
        return bakeMapped(
                subdivisionLevel,
                frameCount,
                forceTwoState,
                (frame, barycentric) -> alphaSampler.opaque(
                        frame,
                        interpolate(u0, u1, u2, barycentric),
                        interpolate(v0, v1, v2, barycentric)));
    }

    private static BakedBlock bakeMapped(
            int subdivisionLevel,
            int frameCount,
            boolean forceTwoState,
            BarycentricAlphaSampler alphaSampler) {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("Alpha coverage must contain at least one frame");
        }
        int microTriangleCount = microTriangleCount(subdivisionLevel);
        byte[] twoState = forceTwoState
                ? new byte[blockByteSize(TWO_STATE_FORMAT, subdivisionLevel)]
                : null;
        byte[] fourState = forceTwoState
                ? null
                : new byte[blockByteSize(FOUR_STATE_FORMAT, subdivisionLevel)];
        float[] barycentric = new float[3];
        boolean hasUnknown = false;
        int subdivision = 1 << subdivisionLevel;
        int row = 0;
        int rowStart = 0;
        int rowEnd = 2 * subdivision - 1;
        for (int cellIndex = 0; cellIndex < microTriangleCount; cellIndex++) {
            if (cellIndex == rowEnd) {
                rowStart = rowEnd;
                row++;
                rowEnd += 2 * (subdivision - row) - 1;
            }
            int birdIndex = 0;
            boolean firstSampleOpaque = false;
            boolean sampleMismatch = false;
            for (int frame = 0; frame < frameCount; frame++) {
                int sampleCount = forceTwoState ? 1 : 4;
                for (int sample = 0; sample < sampleCount; sample++) {
                    samplePoint(
                            row,
                            cellIndex - rowStart,
                            subdivision,
                            sample,
                            barycentric);
                    if (frame == 0 && sample == 0) {
                        birdIndex = barycentricsToSpaceFillingCurveIndex(
                                barycentric[1], barycentric[2], subdivisionLevel);
                    }
                    boolean opaque = alphaSampler.opaque(frame, barycentric);
                    if (frame == 0 && sample == 0) {
                        firstSampleOpaque = opaque;
                    } else {
                        sampleMismatch |= opaque != firstSampleOpaque;
                    }
                }
            }
            // Static power-of-two sprites stay on the two-state fast path. Their adaptive grid is
            // exact for dyadic UVs; non-dyadic model slices receive two refinement levels so the
            // unavoidable binary-grid boundary approximation remains sub-texel. Other inputs use
            // UNKNOWN whenever spatial samples or animation frames disagree.
            boolean opaque = firstSampleOpaque;
            int state = !forceTwoState && sampleMismatch
                    ? STATE_UNKNOWN_OPAQUE
                    : (opaque ? STATE_OPAQUE : STATE_TRANSPARENT);
            hasUnknown |= !forceTwoState && sampleMismatch;
            if (forceTwoState) {
                twoState[birdIndex >>> 3] |= (byte) (state << (birdIndex & 7));
            } else {
                fourState[birdIndex >>> 2] |= (byte) (state << ((birdIndex & 3) * 2));
            }
        }
        if (forceTwoState) {
            return new BakedBlock(TWO_STATE_FORMAT, subdivisionLevel, twoState);
        }
        if (hasUnknown) {
            return new BakedBlock(FOUR_STATE_FORMAT, subdivisionLevel, fourState);
        }
        twoState = new byte[blockByteSize(TWO_STATE_FORMAT, subdivisionLevel)];
        for (int index = 0; index < microTriangleCount; index++) {
            int state = fourState[index >>> 2] >>> ((index & 3) * 2) & 3;
            twoState[index >>> 3] |= (byte) ((state & 1) << (index & 7));
        }
        return new BakedBlock(TWO_STATE_FORMAT, subdivisionLevel, twoState);
    }

    private static void samplePoint(
            int row,
            int remaining,
            int subdivision,
            int sampleIndex,
            float[] target) {
        if (sampleIndex < 0 || sampleIndex >= 4) {
            throw new IndexOutOfBoundsException(sampleIndex);
        }
        int column = remaining / 2;
        boolean upper = (remaining & 1) != 0;
        float inverse = 1.0F / subdivision;
        float x = column * inverse;
        float y = row * inverse;
        float centroid0;
        float centroid1;
        float centroid2;
        if (upper) {
            centroid0 = 1.0F - x - y - 4.0F * inverse / 3.0F;
            centroid1 = x + 2.0F * inverse / 3.0F;
            centroid2 = y + 2.0F * inverse / 3.0F;
        } else {
            centroid0 = 1.0F - x - y - 2.0F * inverse / 3.0F;
            centroid1 = x + inverse / 3.0F;
            centroid2 = y + inverse / 3.0F;
        }
        if (sampleIndex == 0) {
            target[0] = centroid0;
            target[1] = centroid1;
            target[2] = centroid2;
            return;
        }
        int vertex = sampleIndex - 1;
        float vertex1;
        float vertex2;
        if (upper) {
            vertex1 = vertex < 2 ? x + inverse : x;
            vertex2 = vertex == 0 ? y : y + inverse;
        } else {
            vertex1 = vertex == 1 ? x + inverse : x;
            vertex2 = vertex == 2 ? y + inverse : y;
        }
        // Shared microtriangle vertices lie exactly on texel boundaries. Move toward this cell's
        // centroid by one representable float while retaining conservative near-vertex coverage.
        target[1] = Math.nextAfter(vertex1, centroid1);
        target[2] = Math.nextAfter(vertex2, centroid2);
        target[0] = 1.0F - target[1] - target[2];
    }

    private static float interpolate(
            float first, float second, float third, float[] barycentric) {
        return first * barycentric[0]
                + second * barycentric[1]
                + third * barycentric[2];
    }

    private static float unpackHalf(int packed, boolean high) {
        return Float.float16ToFloat((short) (high ? packed >>> 16 : packed));
    }

    private static float clampUnit(float value) {
        return Math.max(0.0F, Math.min(Math.nextDown(1.0F), value));
    }

    @FunctionalInterface
    interface AlphaSampler {
        boolean opaque(int frame, float u, float v);
    }

    @FunctionalInterface
    private interface BarycentricAlphaSampler {
        boolean opaque(int frame, float[] barycentric);
    }

    private static float repeat(float value) {
        float repeated = value - (float) Math.floor(value);
        return Math.min(repeated, Math.nextDown(1.0F));
    }

    private record SpriteAlphaFrames(
            SpritePixelView pixels,
            int width,
            int height,
            int[] frameXs,
            int[] frameYs,
            float atlasU0,
            float atlasV0,
            float atlasUSpan,
            float atlasVSpan) implements AlphaSampler {
        static SpriteAlphaFrames create(CapturedSprite sprite) {
            SpritePixelView pixels = sprite.pixelView();
            if (pixels == null) {
                return null;
            }
            int width = sprite.frameWidth();
            int height = sprite.frameHeight();
            float uSpan = sprite.u1() - sprite.u0();
            float vSpan = sprite.v1() - sprite.v0();
            if (!(Math.abs(uSpan) > 1.0E-12F) || !(Math.abs(vSpan) > 1.0E-12F)) {
                throw new IllegalArgumentException("Sprite atlas span is degenerate");
            }
            int frameCount = sprite.uniqueFrameCount();
            int columns = Math.max(pixels.imageWidth() / width, 1);
            int[] frameXs = new int[frameCount];
            int[] frameYs = new int[frameCount];
            for (int index = 0; index < frameCount; index++) {
                int frame = sprite.uniqueFrame(index);
                frameXs[index] = frame % columns * width;
                frameYs[index] = frame / columns * height;
            }
            return new SpriteAlphaFrames(
                    pixels,
                    width,
                    height,
                    frameXs,
                    frameYs,
                    sprite.u0(),
                    sprite.v0(),
                    uSpan,
                    vSpan);
        }

        @Override
        public boolean opaque(int frame, float u, float v) {
            int pixelX = Math.min((int) (clampUnit(u) * this.width), this.width - 1);
            int pixelY = Math.min((int) (clampUnit(v) * this.height), this.height - 1);
            return this.pixels.argb(
                    this.frameXs[frame] + pixelX,
                    this.frameYs[frame] + pixelY) >>> 24 >= 128;
        }

        int frameCount() {
            return this.frameXs.length;
        }

        boolean staticPowerOfTwo() {
            return this.frameXs.length == 1
                    && isPowerOfTwo(this.width)
                    && isPowerOfTwo(this.height);
        }

        int subdivisionLevel(
                float u0,
                float v0,
                float u1,
                float v1,
                float u2,
                float v2,
                int maximum,
                int repeatedLevel) {
            int textureLevel = ceilLog2(Math.max(this.width, this.height));
            textureLevel = Math.min(textureLevel, MAX_TEXTURE_SUBDIVISION_LEVEL);
            int refinement = this.staticPowerOfTwo()
                            && !dyadicUvMapping(
                                    this.width,
                                    this.height,
                                    u0,
                                    v0,
                                    u1,
                                    v1,
                                    u2,
                                    v2)
                    ? MISALIGNED_UV_REFINEMENT_LEVELS
                    : 0;
            return Math.min(
                    maximum,
                    Math.min(
                            MAX_SUBDIVISION_LEVEL,
                            textureLevel + refinement + repeatedLevel));
        }

        float localU(float atlasU) {
            return clampUnit((atlasU - this.atlasU0) / this.atlasUSpan);
        }

        float localV(float atlasV) {
            return clampUnit((atlasV - this.atlasV0) / this.atlasVSpan);
        }
    }

    static int maximumRepeatedSize(
            CapturedSprite sprite,
            int packedUv0,
            int packedUv1,
            int packedUv2,
            int maximumSubdivisionLevel) {
        return maximumRepeatedSize(
                sprite,
                packedUv0,
                packedUv1,
                packedUv2,
                maximumSubdivisionLevel,
                maximumSubdivisionLevel);
    }

    static int maximumRepeatedSize(
            CapturedSprite sprite,
            int packedUv0,
            int packedUv1,
            int packedUv2,
            int maxTwoStateSubdivisionLevel,
            int maxFourStateSubdivisionLevel) {
        SpriteAlphaFrames frames = SpriteAlphaFrames.create(sprite);
        if (frames == null) {
            return 4;
        }
        int effectiveMaximum = Math.min(
                frames.staticPowerOfTwo()
                        ? maxTwoStateSubdivisionLevel
                        : maxFourStateSubdivisionLevel,
                MAX_SUBDIVISION_LEVEL);
        float u0 = frames.localU(unpackHalf(packedUv0, false));
        float v0 = frames.localV(unpackHalf(packedUv0, true));
        float u1 = frames.localU(unpackHalf(packedUv1, false));
        float v1 = frames.localV(unpackHalf(packedUv1, true));
        float u2 = frames.localU(unpackHalf(packedUv2, false));
        float v2 = frames.localV(unpackHalf(packedUv2, true));
        int baseLevel = frames.subdivisionLevel(
                u0,
                v0,
                u1,
                v1,
                u2,
                v2,
                effectiveMaximum,
                0);
        return 1 << Math.max(
                0,
                Math.min(2, effectiveMaximum - baseLevel));
    }

    private static boolean dyadicUvMapping(
            int width,
            int height,
            float u0,
            float v0,
            float u1,
            float v1,
            float u2,
            float v2) {
        int[] coordinates = {
            alignedTexel(u0 * width),
            alignedTexel(v0 * height),
            alignedTexel(u1 * width),
            alignedTexel(v1 * height),
            alignedTexel(u2 * width),
            alignedTexel(v2 * height)
        };
        for (int coordinate : coordinates) {
            if (coordinate == Integer.MIN_VALUE) {
                return false;
            }
        }
        return dyadicDelta(coordinates[0] - coordinates[2])
                && dyadicDelta(coordinates[0] - coordinates[4])
                && dyadicDelta(coordinates[2] - coordinates[4])
                && dyadicDelta(coordinates[1] - coordinates[3])
                && dyadicDelta(coordinates[1] - coordinates[5])
                && dyadicDelta(coordinates[3] - coordinates[5]);
    }

    private static int alignedTexel(float coordinate) {
        int rounded = Math.round(coordinate);
        return Math.abs(coordinate - rounded) <= TEXEL_ALIGNMENT_EPSILON
                ? rounded
                : Integer.MIN_VALUE;
    }

    private static boolean dyadicDelta(int value) {
        int magnitude = Math.abs(value);
        return magnitude == 0 || isPowerOfTwo(magnitude);
    }

    private static boolean isPowerOfTwo(int value) {
        return value > 0 && (value & value - 1) == 0;
    }

    private static int ceilLog2(int value) {
        return value <= 1 ? 0 : Integer.SIZE - Integer.numberOfLeadingZeros(value - 1);
    }

    /** Khronos' normative barycentric-to-bird-curve reference mapping. */
    static int barycentricsToSpaceFillingCurveIndex(float u, float v, int level) {
        u = Math.max(0.0F, Math.min(1.0F, u));
        v = Math.max(0.0F, Math.min(1.0F, v));
        int scale = 1 << level;
        float fu = u * scale;
        float fv = v * scale;
        int iu = (int) fu;
        int iv = (int) fv;
        float uf = fu - iu;
        float vf = fv - iv;
        iu = Math.min(iu, scale - 1);
        iv = Math.min(iv, scale - 1);
        int iuv = iu + iv;
        if (iuv >= scale) {
            iu -= iuv - scale + 1;
        }
        int iw = ~(iu + iv);
        if (uf + vf >= 1.0F && iuv < scale - 1) {
            --iw;
        }
        int b0 = ~(iu ^ iw) & scale - 1;
        int t = (iu ^ iv) & b0;
        int f = t;
        f ^= f >>> 1;
        f ^= f >>> 2;
        f ^= f >>> 4;
        f ^= f >>> 8;
        int b1 = ((f ^ iu) & ~b0) | t;
        b0 = interleave(b0);
        b1 = interleave(b1);
        return b0 | b1 << 1;
    }

    private static int interleave(int value) {
        value = (value | value << 8) & 0x00ff00ff;
        value = (value | value << 4) & 0x0f0f0f0f;
        value = (value | value << 2) & 0x33333333;
        return (value | value << 1) & 0x55555555;
    }

    record BakedBlock(int format, int subdivisionLevel, byte[] states) {
        BakedBlock(int format, byte[] states) {
            this(format, SUBDIVISION_LEVEL, states);
        }

        BakedBlock {
            if (states.length != blockByteSize(format, subdivisionLevel)) {
                throw new IllegalArgumentException(
                        "Opacity micromap block size does not match its format");
            }
        }

        int state(int index) {
            if (index < 0 || index >= microTriangleCount(this.subdivisionLevel)) {
                throw new IndexOutOfBoundsException(index);
            }
            return this.format == TWO_STATE_FORMAT
                    ? this.states[index >>> 3] >>> (index & 7) & 1
                    : this.states[index >>> 2] >>> ((index & 3) * 2) & 3;
        }
    }

    private static final class BlockKey {
        private final int format;
        private final int subdivisionLevel;
        private final byte[] data;
        private final int hash;

        private BlockKey(int format, int subdivisionLevel, byte[] data) {
            this.format = format;
            this.subdivisionLevel = subdivisionLevel;
            this.data = data;
            this.hash = 31 * (31 * format + subdivisionLevel) + Arrays.hashCode(data);
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof BlockKey key
                            && this.format == key.format
                            && this.subdivisionLevel == key.subdivisionLevel
                            && Arrays.equals(this.data, key.data);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    private record BakeKey(
            CapturedSprite sprite, int packedUv0, int packedUv1, int packedUv2) {
    }

    private record ConstantBakeKey(
            CapturedSprite sprite, int floatU, int floatV) {
    }

    private record RepeatedBakeKey(
            CapturedSprite sprite,
            int packedUv0,
            int packedUv1,
            int packedUv2,
            int size,
            float projectedU0,
            float projectedV0,
            float projectedU1,
            float projectedV1,
            float projectedU2,
            float projectedV2) {
    }
}
