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

/** Immutable CPU build input for alpha-cutout geometry's mixed-format Vulkan opacity micromap. */
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
            new OpacityMicromapData(new byte[0], new int[0], new int[0], new int[0]);

    private final byte[] blocks;
    private final int[] blockOffsets;
    private final int[] blockFormats;
    private final int[] triangleIndices;

    private OpacityMicromapData(
            byte[] blocks,
            int[] blockOffsets,
            int[] blockFormats,
            int[] triangleIndices) {
        if (blockOffsets.length != blockFormats.length) {
            throw new IllegalArgumentException("Opacity micromap block metadata is inconsistent");
        }
        int expectedOffset = 0;
        for (int index = 0; index < blockFormats.length; index++) {
            if (blockOffsets[index] != expectedOffset) {
                throw new IllegalArgumentException("Opacity micromap blocks are not tightly packed");
            }
            expectedOffset = Math.addExact(expectedOffset, blockByteSize(blockFormats[index]));
        }
        if (expectedOffset != blocks.length) {
            throw new IllegalArgumentException("Opacity micromap byte storage is inconsistent");
        }
        this.blocks = blocks;
        this.blockOffsets = blockOffsets;
        this.blockFormats = blockFormats;
        this.triangleIndices = triangleIndices;
    }

    public byte[] blocks() {
        return this.blocks;
    }

    public int[] triangleIndices() {
        return this.triangleIndices;
    }

    public int[] blockOffsets() {
        return this.blockOffsets;
    }

    public int[] blockFormats() {
        return this.blockFormats;
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

    public static int blockByteSize(int format) {
        return switch (format) {
            case TWO_STATE_FORMAT -> TWO_STATE_BYTES_PER_BLOCK;
            case FOUR_STATE_FORMAT -> FOUR_STATE_BYTES_PER_BLOCK;
            default -> throw new IllegalArgumentException(
                    "Unsupported opacity micromap format: " + format);
        };
    }

    public boolean isEmpty() {
        return this.triangleIndices.length == 0;
    }

    public long byteSize() {
        return (long) this.blocks.length
                + (long) this.blockCount() * org.lwjgl.vulkan.VkMicromapTriangleEXT.SIZEOF
                + (long) this.triangleIndices.length * Integer.BYTES;
    }

    static OpacityMicromapData fullyUnknown(int triangleCount) {
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
                new byte[0], new int[0], new int[0], indices);
    }

    /** Per-mesh builder. Cluster merging reuses the same content interning path. */
    public static final class Builder {
        private final ArrayList<BakedBlock> blocks = new ArrayList<>();
        private final Map<BlockKey, Integer> blockIndices = new HashMap<>();
        private final Map<BakeKey, Integer> bakedTriangles = new HashMap<>();
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
                    BakedBlock block = new BakedBlock(
                            format,
                            Arrays.copyOfRange(
                                    data.blocks, offset, offset + blockByteSize(format)));
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
            for (int index = 0; index < MICRO_TRIANGLE_COUNT; index++) {
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
            BlockKey key = new BlockKey(block.format, block.states);
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
        int destination = 0;
        for (int index = 0; index < blocks.length; index++) {
            BakedBlock block = blocks[index];
            blockOffsets[index] = destination;
            blockFormats[index] = block.format;
            System.arraycopy(
                    block.states, 0, packedBlocks, destination, block.states.length);
            destination += block.states.length;
        }
        return new OpacityMicromapData(
                packedBlocks, blockOffsets, blockFormats, triangleIndices);
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

    static BakedBlock bakeCoverage(
            float u0,
            float v0,
            float u1,
            float v1,
            float u2,
            float v2,
            int frameCount,
            AlphaSampler alphaSampler) {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("Alpha coverage must contain at least one frame");
        }
        byte[] fourState = new byte[FOUR_STATE_BYTES_PER_BLOCK];
        float[] barycentric = new float[3];
        boolean hasUnknown = false;
        for (int cellIndex = 0; cellIndex < MICRO_TRIANGLE_COUNT; cellIndex++) {
            EmissionDistribution.Cell cell = EmissionDistribution.cell(cellIndex);
            int birdIndex = 0;
            boolean firstFrameOpaque = false;
            boolean frameMismatch = false;
            for (int frame = 0; frame < frameCount; frame++) {
                int opaqueSamples = 0;
                for (int sample = 0; sample < 4; sample++) {
                    cell.samplePoint(sample, barycentric);
                    if (frame == 0 && sample == 0) {
                        birdIndex = barycentricsToSpaceFillingCurveIndex(
                                barycentric[1], barycentric[2], SUBDIVISION_LEVEL);
                    }
                    float u = interpolate(u0, u1, u2, barycentric);
                    float v = interpolate(v0, v1, v2, barycentric);
                    if (alphaSampler.opaque(frame, u, v)) {
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
            return new BakedBlock(FOUR_STATE_FORMAT, fourState);
        }
        byte[] twoState = new byte[TWO_STATE_BYTES_PER_BLOCK];
        for (int index = 0; index < MICRO_TRIANGLE_COUNT; index++) {
            int state = fourState[index >>> 2] >>> ((index & 3) * 2) & 3;
            twoState[index >>> 3] |= (byte) ((state & 1) << (index & 7));
        }
        return new BakedBlock(TWO_STATE_FORMAT, twoState);
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

    record BakedBlock(int format, byte[] states) {
        BakedBlock {
            if (states.length != blockByteSize(format)) {
                throw new IllegalArgumentException(
                        "Opacity micromap block size does not match its format");
            }
        }

        int state(int index) {
            if (index < 0 || index >= MICRO_TRIANGLE_COUNT) {
                throw new IndexOutOfBoundsException(index);
            }
            return this.format == TWO_STATE_FORMAT
                    ? this.states[index >>> 3] >>> (index & 7) & 1
                    : this.states[index >>> 2] >>> ((index & 3) * 2) & 3;
        }
    }

    private static final class BlockKey {
        private final int format;
        private final byte[] data;
        private final int hash;

        private BlockKey(int format, byte[] data) {
            this.format = format;
            this.data = data;
            this.hash = 31 * format + Arrays.hashCode(data);
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof BlockKey key
                            && this.format == key.format
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
}
