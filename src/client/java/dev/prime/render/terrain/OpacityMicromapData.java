package dev.prime.render.terrain;

import com.mojang.blaze3d.platform.NativeImage;
import dev.prime.mixin.SpriteContentsAccessor;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.vulkan.EXTOpacityMicromap;

/** Immutable CPU build input for the alpha-cutout geometry's Vulkan opacity micromap. */
public final class OpacityMicromapData {
    public static final int SUBDIVISION_LEVEL = 4;
    public static final int MICRO_TRIANGLE_COUNT = 1 << (2 * SUBDIVISION_LEVEL);
    public static final int BYTES_PER_BLOCK = MICRO_TRIANGLE_COUNT * 2 / Byte.SIZE;

    private static final int STATE_OPAQUE = 1;
    private static final int STATE_UNKNOWN_OPAQUE = 3;

    public static final OpacityMicromapData EMPTY =
            new OpacityMicromapData(new byte[0], new int[0]);

    private final byte[] blocks;
    private final int[] triangleIndices;

    private OpacityMicromapData(byte[] blocks, int[] triangleIndices) {
        if (blocks.length % BYTES_PER_BLOCK != 0) {
            throw new IllegalArgumentException("Opacity micromap blocks are not tightly packed");
        }
        this.blocks = blocks;
        this.triangleIndices = triangleIndices;
    }

    public byte[] blocks() {
        return this.blocks;
    }

    public int[] triangleIndices() {
        return this.triangleIndices;
    }

    public int blockCount() {
        return this.blocks.length / BYTES_PER_BLOCK;
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
        return new OpacityMicromapData(new byte[0], indices);
    }

    /** Per-mesh builder. Cluster merging reuses the same content interning path. */
    public static final class Builder {
        private final ArrayList<byte[]> blocks = new ArrayList<>();
        private final Map<BlockKey, Integer> blockIndices = new HashMap<>();
        private final Map<BakeKey, Integer> bakedTriangles = new HashMap<>();
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
            byte[] block;
            try {
                block = bake(sprite, packedUv0, packedUv1, packedUv2);
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
                    int offset = Math.multiplyExact(sourceIndex, BYTES_PER_BLOCK);
                    byte[] block = Arrays.copyOfRange(
                            data.blocks, offset, offset + BYTES_PER_BLOCK);
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
            byte[] packedBlocks = new byte[Math.multiplyExact(this.blocks.size(), BYTES_PER_BLOCK)];
            int destination = 0;
            for (byte[] block : this.blocks) {
                System.arraycopy(block, 0, packedBlocks, destination, BYTES_PER_BLOCK);
                destination += BYTES_PER_BLOCK;
            }
            return new OpacityMicromapData(
                    packedBlocks, Arrays.copyOf(this.triangleIndices, this.triangleCount));
        }

        private int intern(byte[] block) {
            boolean allOpaque = true;
            boolean allUnknown = true;
            for (int index = 0; index < MICRO_TRIANGLE_COUNT; index++) {
                int state = block[index >>> 2] >>> ((index & 3) * 2) & 3;
                allOpaque &= state == STATE_OPAQUE;
                allUnknown &= state == STATE_UNKNOWN_OPAQUE;
            }
            if (allOpaque) {
                return EXTOpacityMicromap.VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_OPAQUE_EXT;
            }
            if (allUnknown) {
                return EXTOpacityMicromap
                        .VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_UNKNOWN_OPAQUE_EXT;
            }
            BlockKey key = new BlockKey(block);
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

    private static byte[] bake(
            TextureAtlasSprite sprite, int packedUv0, int packedUv1, int packedUv2) {
        byte[] result = new byte[BYTES_PER_BLOCK];
        float[] barycentric = new float[3];
        for (int cellIndex = 0; cellIndex < MICRO_TRIANGLE_COUNT; cellIndex++) {
            EmissionDistribution.Cell cell = EmissionDistribution.cell(cellIndex);
            boolean opaque = true;
            for (int sample = 0; sample < 4 && opaque; sample++) {
                cell.samplePoint(sample, barycentric);
                float u = interpolatePacked(
                        packedUv0, packedUv1, packedUv2, barycentric, false);
                float v = interpolatePacked(
                        packedUv0, packedUv1, packedUv2, barycentric, true);
                opaque = sampleAndGuardOpaque(sprite, u, v);
            }
            int birdIndex = barycentricsToSpaceFillingCurveIndex(
                    barycentric[1], barycentric[2], SUBDIVISION_LEVEL);
            int state = opaque ? STATE_OPAQUE : STATE_UNKNOWN_OPAQUE;
            result[birdIndex >>> 2] |= (byte) (state << ((birdIndex & 3) * 2));
        }
        return result;
    }

    private static boolean sampleAndGuardOpaque(TextureAtlasSprite sprite, float atlasU, float atlasV) {
        SpriteContents contents = sprite.contents();
        float uSpan = sprite.getU1() - sprite.getU0();
        float vSpan = sprite.getV1() - sprite.getV0();
        if (!(Math.abs(uSpan) > 1.0E-12F) || !(Math.abs(vSpan) > 1.0E-12F)) {
            return false;
        }
        float localU = clampUnit((atlasU - sprite.getU0()) / uSpan);
        float localV = clampUnit((atlasV - sprite.getV0()) / vSpan);
        IntList frames = contents.isAnimated() ? contents.getUniqueFrames() : null;
        int frameCount = frames == null ? 1 : frames.size();
        SpriteContentsAccessor accessor = (SpriteContentsAccessor) (Object) contents;
        NativeImage[] mipImages = accessor.prime$byMipLevel();
        if (mipImages == null || mipImages.length == 0) {
            mipImages = new NativeImage[] {accessor.prime$originalImage()};
        }
        for (int mipLevel = 0; mipLevel < mipImages.length; mipLevel++) {
            NativeImage image = mipImages[mipLevel];
            int contentWidth = Math.max(contents.width() >> mipLevel, 1);
            int contentHeight = Math.max(contents.height() >> mipLevel, 1);
            int pixelX = Math.min((int) (localU * contentWidth), contentWidth - 1);
            int pixelY = Math.min((int) (localV * contentHeight), contentHeight - 1);
            int columns = Math.max(image.getWidth() / contentWidth, 1);
            for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                int frame = frames == null ? 0 : frames.getInt(frameIndex);
                int frameX = frame % columns * contentWidth;
                int frameY = frame / columns * contentHeight;
                // The guard includes bilinear neighbours. Requiring it at every generated mip
                // also covers trilinear interpolation and keeps OMM classification equivalent to
                // the ray-cone LOD used by the fallback any-hit shader.
                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    int sourceY = frameY
                            + Math.max(0, Math.min(contentHeight - 1, pixelY + offsetY));
                    for (int offsetX = -1; offsetX <= 1; offsetX++) {
                        int sourceX = frameX
                                + Math.max(0, Math.min(contentWidth - 1, pixelX + offsetX));
                        if (image.getPixel(sourceX, sourceY) >>> 24 < 128) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static float interpolatePacked(
            int packed0, int packed1, int packed2, float[] barycentric, boolean high) {
        return unpackHalf(packed0, high) * barycentric[0]
                + unpackHalf(packed1, high) * barycentric[1]
                + unpackHalf(packed2, high) * barycentric[2];
    }

    private static float unpackHalf(int packed, boolean high) {
        return Float.float16ToFloat((short) (high ? packed >>> 16 : packed));
    }

    private static float clampUnit(float value) {
        return Math.max(0.0F, Math.min(Math.nextDown(1.0F), value));
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

    private static final class BlockKey {
        private final byte[] data;
        private final int hash;

        private BlockKey(byte[] data) {
            this.data = data;
            this.hash = Arrays.hashCode(data);
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof BlockKey key && Arrays.equals(this.data, key.data);
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
