package dev.prime.render.terrain;

import com.mojang.blaze3d.platform.NativeImage;
import dev.prime.mixin.SpriteContentsAccessor;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.vulkan.EXTOpacityMicromap;

/**
 * Logically immutable CPU build input for alpha-cutout geometry's mixed-format Vulkan opacity
 * micromap.
 *
 * <p>Builders transfer ownership of the packed arrays. Public array access is a borrowed read-only
 * view used by serialization and Vulkan upload; mutating it violates the scene-value contract.
 */
public final class OpacityMicromapData {
    public static final int SUBDIVISION_LEVEL = 4;
    public static final int MICRO_TRIANGLE_COUNT = 1 << (2 * SUBDIVISION_LEVEL);
    public static final int TWO_STATE_FORMAT =
            EXTOpacityMicromap.VK_OPACITY_MICROMAP_FORMAT_2_STATE_EXT;
    public static final int FOUR_STATE_FORMAT =
            EXTOpacityMicromap.VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT;
    public static final int TWO_STATE_BYTES_PER_BLOCK =
            (MICRO_TRIANGLE_COUNT + Byte.SIZE - 1) / Byte.SIZE;
    public static final int FOUR_STATE_BYTES_PER_BLOCK =
            (MICRO_TRIANGLE_COUNT * 2 + Byte.SIZE - 1) / Byte.SIZE;

    private static final int STATE_TRANSPARENT = 0;
    private static final int STATE_OPAQUE = 1;
    private static final int STATE_UNKNOWN_OPAQUE = 3;

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
                + (long) this.blockCount() * org.lwjgl.vulkan.VkMicromapTriangleEXT.SIZEOF
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
                EXTOpacityMicromap.VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_UNKNOWN_OPAQUE_EXT);
        return new OpacityMicromapData(
                new byte[0], new int[0], new int[0], new int[0], indices);
    }

    private static void requireValidTriangleIndices(
            int[] triangleIndices, int blockCount) {
        for (int index : triangleIndices) {
            if (index >= blockCount
                    || index < EXTOpacityMicromap
                            .VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_UNKNOWN_OPAQUE_EXT) {
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
        private final Map<RepeatedBakeKey, Integer> bakedRepeatedTriangles = new HashMap<>();
        private final Map<TextureAtlasSprite, SpriteAlphaFrames> alphaFrames =
                new IdentityHashMap<>();
        private int[] triangleIndices = new int[32];
        private int triangleCount;

        public void addTriangle(
                TextureAtlasSprite sprite,
                int packedUv0,
                int packedUv1,
                int packedUv2) {
            BakeKey key = new BakeKey(sprite, packedUv0, packedUv1, packedUv2);
            Integer cached = this.bakedTriangles.get(key);
            if (cached != null) {
                this.addIndex(cached);
                return;
            }
            int index;
            BakedBlock block;
            try {
                SpriteAlphaFrames frames =
                        this.alphaFrames.computeIfAbsent(sprite, SpriteAlphaFrames::create);
                block = bake(frames, packedUv0, packedUv1, packedUv2);
            } catch (RuntimeException exception) {
                // A cancelled mesh task can overlap resource teardown. Unknown-opaque preserves
                // the existing any-hit path and therefore remains correct during reload races.
                index = EXTOpacityMicromap
                        .VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_UNKNOWN_OPAQUE_EXT;
                this.bakedTriangles.put(key, index);
                this.addIndex(index);
                return;
            }
            index = this.intern(block);
            this.bakedTriangles.put(key, index);
            this.addIndex(index);
        }

        public void addRepeatedTriangle(
                TextureAtlasSprite sprite,
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
            int index;
            try {
                SpriteAlphaFrames frames =
                        this.alphaFrames.computeIfAbsent(sprite, SpriteAlphaFrames::create);
                int subdivisionLevel =
                        SUBDIVISION_LEVEL + Integer.numberOfTrailingZeros(size);
                index = this.intern(bakeRepeated(
                        frames,
                        packedUv0,
                        packedUv1,
                        packedUv2,
                        subdivisionLevel,
                        projectedU0,
                        projectedV0,
                        projectedU1,
                        projectedV1,
                        projectedU2,
                        projectedV2));
            } catch (RuntimeException exception) {
                index = EXTOpacityMicromap
                        .VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_UNKNOWN_OPAQUE_EXT;
            }
            this.bakedRepeatedTriangles.put(key, index);
            this.addIndex(index);
        }

        public void addFullyUnknownTriangle() {
            this.addIndex(EXTOpacityMicromap
                    .VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_UNKNOWN_OPAQUE_EXT);
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
                return EXTOpacityMicromap.VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_OPAQUE_EXT;
            }
            if (allTransparent) {
                return EXTOpacityMicromap
                        .VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_TRANSPARENT_EXT;
            }
            if (allUnknownOpaque) {
                return EXTOpacityMicromap
                        .VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_UNKNOWN_OPAQUE_EXT;
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
            SpriteAlphaFrames frames, int packedUv0, int packedUv1, int packedUv2) {
        float u0 = frames.localU(unpackHalf(packedUv0, false));
        float v0 = frames.localV(unpackHalf(packedUv0, true));
        float u1 = frames.localU(unpackHalf(packedUv1, false));
        float v1 = frames.localV(unpackHalf(packedUv1, true));
        float u2 = frames.localU(unpackHalf(packedUv2, false));
        float v2 = frames.localV(unpackHalf(packedUv2, true));
        return bakeCoverage(
                u0, v0, u1, v1, u2, v2, frames.frameCount(), frames);
    }

    private static BakedBlock bakeRepeated(
            SpriteAlphaFrames frames,
            int packedUv0,
            int packedUv1,
            int packedUv2,
            int subdivisionLevel,
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
                subdivisionLevel,
                frames.frameCount(),
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
        return bakeMapped(
                subdivisionLevel,
                frameCount,
                (frame, barycentric) -> alphaSampler.opaque(
                        frame,
                        interpolate(u0, u1, u2, barycentric),
                        interpolate(v0, v1, v2, barycentric)));
    }

    private static BakedBlock bakeMapped(
            int subdivisionLevel,
            int frameCount,
            BarycentricAlphaSampler alphaSampler) {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("Alpha coverage must contain at least one frame");
        }
        int microTriangleCount = microTriangleCount(subdivisionLevel);
        byte[] fourState = new byte[blockByteSize(FOUR_STATE_FORMAT, subdivisionLevel)];
        float[] barycentric = new float[3];
        boolean hasUnknown = false;
        for (int cellIndex = 0; cellIndex < microTriangleCount; cellIndex++) {
            int birdIndex = 0;
            boolean firstFrameOpaque = false;
            boolean frameMismatch = false;
            for (int frame = 0; frame < frameCount; frame++) {
                int opaqueSamples = 0;
                for (int sample = 0; sample < 4; sample++) {
                    samplePoint(cellIndex, subdivisionLevel, sample, barycentric);
                    if (frame == 0 && sample == 0) {
                        birdIndex = barycentricsToSpaceFillingCurveIndex(
                                barycentric[1], barycentric[2], subdivisionLevel);
                    }
                    if (alphaSampler.opaque(frame, barycentric)) {
                        opaqueSamples++;
                    }
                }
                // At native Minecraft resolution both microtriangles of a texel agree exactly.
                // Higher-resolution packs receive a coverage-majority approximation instead of
                // the old neighbour veto, which systematically eroded cutout boundaries.
                boolean frameOpaque = opaqueSamples * 2 >= 4;
                if (frame == 0) {
                    firstFrameOpaque = frameOpaque;
                } else {
                    frameMismatch |= frameOpaque != firstFrameOpaque;
                }
            }
            int state = frameMismatch
                    ? STATE_UNKNOWN_OPAQUE
                    : (firstFrameOpaque ? STATE_OPAQUE : STATE_TRANSPARENT);
            hasUnknown |= frameMismatch;
            fourState[birdIndex >>> 2] |= (byte) (state << ((birdIndex & 3) * 2));
        }
        if (hasUnknown) {
            return new BakedBlock(FOUR_STATE_FORMAT, subdivisionLevel, fourState);
        }
        byte[] twoState = new byte[blockByteSize(TWO_STATE_FORMAT, subdivisionLevel)];
        for (int index = 0; index < microTriangleCount; index++) {
            int state = fourState[index >>> 2] >>> ((index & 3) * 2) & 3;
            twoState[index >>> 3] |= (byte) ((state & 1) << (index & 7));
        }
        return new BakedBlock(TWO_STATE_FORMAT, subdivisionLevel, twoState);
    }

    private static void samplePoint(
            int cellIndex, int subdivisionLevel, int sampleIndex, float[] target) {
        if (sampleIndex < 0 || sampleIndex >= 4) {
            throw new IndexOutOfBoundsException(sampleIndex);
        }
        int subdivision = 1 << subdivisionLevel;
        int remaining = cellIndex;
        int row = 0;
        while (row < subdivision) {
            int rowCount = 2 * (subdivision - row) - 1;
            if (remaining < rowCount) {
                break;
            }
            remaining -= rowCount;
            row++;
        }
        if (row == subdivision) {
            throw new IndexOutOfBoundsException(cellIndex);
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
        target[1] = (centroid1 + vertex1) * 0.5F;
        target[2] = (centroid2 + vertex2) * 0.5F;
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
            NativeImage image,
            int width,
            int height,
            int[] frameXs,
            int[] frameYs,
            float atlasU0,
            float atlasV0,
            float atlasUSpan,
            float atlasVSpan) implements AlphaSampler {
        static SpriteAlphaFrames create(TextureAtlasSprite sprite) {
            SpriteContents contents = sprite.contents();
            SpriteContentsAccessor accessor = (SpriteContentsAccessor) (Object) contents;
            NativeImage image = accessor.prime$originalImage();
            int width = contents.width();
            int height = contents.height();
            if (image == null || width <= 0 || height <= 0) {
                throw new IllegalStateException("Sprite alpha source is unavailable");
            }
            float uSpan = sprite.getU1() - sprite.getU0();
            float vSpan = sprite.getV1() - sprite.getV0();
            if (!(Math.abs(uSpan) > 1.0E-12F) || !(Math.abs(vSpan) > 1.0E-12F)) {
                throw new IllegalArgumentException("Sprite atlas span is degenerate");
            }
            IntList frames = contents.isAnimated() ? contents.getUniqueFrames() : null;
            int frameCount = frames == null || frames.isEmpty() ? 1 : frames.size();
            int columns = Math.max(image.getWidth() / width, 1);
            int[] frameXs = new int[frameCount];
            int[] frameYs = new int[frameCount];
            for (int index = 0; index < frameCount; index++) {
                int frame = frames == null || frames.isEmpty() ? 0 : frames.getInt(index);
                frameXs[index] = frame % columns * width;
                frameYs[index] = frame / columns * height;
            }
            return new SpriteAlphaFrames(
                    image,
                    width,
                    height,
                    frameXs,
                    frameYs,
                    sprite.getU0(),
                    sprite.getV0(),
                    uSpan,
                    vSpan);
        }

        @Override
        public boolean opaque(int frame, float u, float v) {
            int pixelX = Math.min((int) (clampUnit(u) * this.width), this.width - 1);
            int pixelY = Math.min((int) (clampUnit(v) * this.height), this.height - 1);
            return this.image.getPixel(
                    this.frameXs[frame] + pixelX,
                    this.frameYs[frame] + pixelY) >>> 24 >= 128;
        }

        int frameCount() {
            return this.frameXs.length;
        }

        float localU(float atlasU) {
            return clampUnit((atlasU - this.atlasU0) / this.atlasUSpan);
        }

        float localV(float atlasV) {
            return clampUnit((atlasV - this.atlasV0) / this.atlasVSpan);
        }
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
            TextureAtlasSprite sprite, int packedUv0, int packedUv1, int packedUv2) {
    }

    private record RepeatedBakeKey(
            TextureAtlasSprite sprite,
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
