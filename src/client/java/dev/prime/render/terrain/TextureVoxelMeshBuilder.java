package dev.prime.render.terrain;

import com.mojang.blaze3d.platform.NativeImage;
import dev.prime.mixin.SpriteContentsAccessor;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Interns one pixel-height mesh per texture/UV/orientation and records lightweight face instances.
 */
final class TextureVoxelMeshBuilder {
    static final float MAXIMUM_HEIGHT = 1.0F / 32.0F;

    private final boolean buildOpacityMicromap;
    private final Map<Key, Integer> meshIndices = new HashMap<>();
    private final Set<Key> rejected = new HashSet<>();
    private final ArrayList<CpuVoxelMesh> meshes = new ArrayList<>();
    private final CpuVoxelInstances.Builder instances = new CpuVoxelInstances.Builder();

    TextureVoxelMeshBuilder(boolean buildOpacityMicromap) {
        this.buildOpacityMicromap = buildOpacityMicromap;
    }

    boolean add(MergeFace face) {
        int flags = PrimitivePacking.unpackFlags(
                face.primitive()[3], face.primitive()[5])
                & ~PrimitivePacking.FLAG_TANGENT_NEGATIVE;
        Key key = new Key(
                face.sprite(),
                face.planeAxis(),
                face.normalSign(),
                face.primitive()[0],
                face.primitive()[1],
                face.primitive()[2],
                flags);
        if (this.rejected.contains(key)) {
            return false;
        }
        Integer meshIndex = this.meshIndices.get(key);
        if (meshIndex == null) {
            CpuVoxelMesh mesh;
            try {
                mesh = buildMesh(key, this.buildOpacityMicromap);
            } catch (IllegalArgumentException
                    | IllegalStateException
                    | ArithmeticException exception) {
                this.rejected.add(key);
                return false;
            }
            meshIndex = this.meshes.size();
            this.meshes.add(mesh);
            this.meshIndices.put(key, meshIndex);
        }
        float translationX;
        float translationY;
        float translationZ;
        switch (face.planeAxis()) {
            case 0 -> {
                translationX = face.plane();
                translationY = face.cellU();
                translationZ = face.cellV();
            }
            case 1 -> {
                translationX = face.cellU();
                translationY = face.plane();
                translationZ = face.cellV();
            }
            case 2 -> {
                translationX = face.cellU();
                translationY = face.cellV();
                translationZ = face.plane();
            }
            default -> throw new IllegalArgumentException("Invalid face plane axis");
        }
        this.instances.add(
                meshIndex,
                face.primitive()[3] & 0x00ff_ffff,
                translationX,
                translationY,
                translationZ);
        return true;
    }

    ListResult build() {
        return new ListResult(List.copyOf(this.meshes), this.instances.build());
    }

    static float heightFromArgb(int argb) {
        int red = argb >>> 16 & 0xff;
        int green = argb >>> 8 & 0xff;
        int blue = argb & 0xff;
        // Fixed-point BT.601 Y' coefficients preserve exact black and white endpoints.
        float luma = (77 * red + 150 * green + 29 * blue) / (255.0F * 256.0F);
        return luma * MAXIMUM_HEIGHT;
    }

    private static CpuVoxelMesh buildMesh(Key key, boolean buildOpacityMicromap) {
        SpritePixels pixels = SpritePixels.create(key.sprite);
        if (pixels.width != pixels.height) {
            throw new IllegalArgumentException(
                    "Voxel-surface textures must have square animation frames");
        }
        int size = pixels.width;
        float[] heights = new float[Math.multiplyExact(size, size)];
        float[] atlasUs = new float[heights.length];
        float[] atlasVs = new float[heights.length];
        UvTransform uv = new UvTransform(
                key.packedUv0, key.packedUv1, key.packedUv2);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int index = x + y * size;
                float u = (x + 0.5F) / size;
                float v = (y + 0.5F) / size;
                float atlasU = uv.u(u, v);
                float atlasV = uv.v(u, v);
                atlasUs[index] = atlasU;
                atlasVs[index] = atlasV;
                heights[index] = heightFromArgb(pixels.sample(atlasU, atlasV, key.sprite));
            }
        }
        return buildHeightField(
                key,
                size,
                heights,
                atlasUs,
                atlasVs,
                buildOpacityMicromap);
    }

    static CpuVoxelMesh buildOpaqueHeightField(
            int size, int[] argb, int planeAxis, int normalSign) {
        if (size <= 0
                || argb.length != Math.multiplyExact(size, size)
                || planeAxis < 0
                || planeAxis > 2
                || Math.abs(normalSign) != 1) {
            throw new IllegalArgumentException(
                    "Invalid source for an opaque voxel height field");
        }
        float[] heights = new float[argb.length];
        float[] atlasUs = new float[argb.length];
        float[] atlasVs = new float[argb.length];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int index = x + y * size;
                heights[index] = heightFromArgb(argb[index]);
                atlasUs[index] = (x + 0.5F) / size;
                atlasVs[index] = (y + 0.5F) / size;
            }
        }
        Key key = new Key(
                null,
                planeAxis,
                normalSign,
                PrimitivePacking.packHalf2(0.0F, 0.0F),
                PrimitivePacking.packHalf2(1.0F, 0.0F),
                PrimitivePacking.packHalf2(0.0F, 1.0F),
                0);
        return buildHeightField(
                key, size, heights, atlasUs, atlasVs, false);
    }

    private static CpuVoxelMesh buildHeightField(
            Key key,
            int size,
            float[] heights,
            float[] atlasUs,
            float[] atlasVs,
            boolean buildOpacityMicromap) {
        Mesh mesh = new Mesh(key, buildOpacityMicromap);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int index = x + y * size;
                float minimumU = x / (float) size;
                float maximumU = (x + 1) / (float) size;
                float minimumV = y / (float) size;
                float maximumV = (y + 1) / (float) size;
                float height = heights[index];
                mesh.addTop(
                        minimumU,
                        minimumV,
                        maximumU,
                        maximumV,
                        height,
                        atlasUs[index],
                        atlasVs[index]);
                if (x == 0 || height > heights[index - 1]) {
                    mesh.addWall(
                            0,
                            minimumU,
                            minimumV,
                            maximumV,
                            x == 0 ? 0.0F : heights[index - 1],
                            height,
                            -1,
                            atlasUs[index],
                            atlasVs[index]);
                }
                if (x == size - 1 || height > heights[index + 1]) {
                    mesh.addWall(
                            0,
                            maximumU,
                            minimumV,
                            maximumV,
                            x == size - 1 ? 0.0F : heights[index + 1],
                            height,
                            1,
                            atlasUs[index],
                            atlasVs[index]);
                }
                if (y == 0 || height > heights[index - size]) {
                    mesh.addWall(
                            1,
                            minimumV,
                            minimumU,
                            maximumU,
                            y == 0 ? 0.0F : heights[index - size],
                            height,
                            -1,
                            atlasUs[index],
                            atlasVs[index]);
                }
                if (y == size - 1 || height > heights[index + size]) {
                    mesh.addWall(
                            1,
                            maximumV,
                            minimumU,
                            maximumU,
                            y == size - 1 ? 0.0F : heights[index + size],
                            height,
                            1,
                            atlasUs[index],
                            atlasVs[index]);
                }
            }
        }
        return mesh.build();
    }

    record ListResult(List<CpuVoxelMesh> meshes, CpuVoxelInstances instances) {
        ListResult {
            meshes = List.copyOf(meshes);
        }
    }

    private record Key(
            TextureAtlasSprite sprite,
            int planeAxis,
            int normalSign,
            int packedUv0,
            int packedUv1,
            int packedUv2,
            int flags) {
    }

    private record UvTransform(
            float u0, float v0, float u1, float v1, float u2, float v2) {
        UvTransform(int packed0, int packed1, int packed2) {
            this(
                    unpackHalf(packed0, false),
                    unpackHalf(packed0, true),
                    unpackHalf(packed1, false),
                    unpackHalf(packed1, true),
                    unpackHalf(packed2, false),
                    unpackHalf(packed2, true));
        }

        float u(float x, float y) {
            return this.u0 + x * (this.u1 - this.u0) + y * (this.u2 - this.u0);
        }

        float v(float x, float y) {
            return this.v0 + x * (this.v1 - this.v0) + y * (this.v2 - this.v0);
        }
    }

    private record SpritePixels(
            NativeImage image,
            int width,
            int height,
            int frameX,
            int frameY) {
        static SpritePixels create(TextureAtlasSprite sprite) {
            SpriteContents contents = sprite.contents();
            NativeImage image =
                    ((SpriteContentsAccessor) (Object) contents).prime$originalImage();
            int width = contents.width();
            int height = contents.height();
            if (image == null || image.isClosed() || width <= 0 || height <= 0) {
                throw new IllegalStateException(
                        "Voxel-surface texture pixels are unavailable");
            }
            IntList frames = contents.isAnimated() ? contents.getUniqueFrames() : null;
            int firstFrame = frames == null || frames.isEmpty() ? 0 : frames.getInt(0);
            int columns = Math.max(image.getWidth() / width, 1);
            return new SpritePixels(
                    image,
                    width,
                    height,
                    firstFrame % columns * width,
                    firstFrame / columns * height);
        }

        int sample(float atlasU, float atlasV, TextureAtlasSprite sprite) {
            float uSpan = sprite.getU1() - sprite.getU0();
            float vSpan = sprite.getV1() - sprite.getV0();
            if (!(Math.abs(uSpan) > 1.0E-12F)
                    || !(Math.abs(vSpan) > 1.0E-12F)) {
                throw new IllegalArgumentException(
                        "Voxel-surface sprite atlas span is degenerate");
            }
            float localU = clampUnit((atlasU - sprite.getU0()) / uSpan);
            float localV = clampUnit((atlasV - sprite.getV0()) / vSpan);
            int x = Math.min((int) (localU * this.width), this.width - 1);
            int y = Math.min((int) (localV * this.height), this.height - 1);
            return this.image.getPixel(this.frameX + x, this.frameY + y);
        }
    }

    private static final class Mesh {
        private final Key key;
        private final boolean cutout;
        private final boolean transmissive;
        private final boolean cutoutGeometry;
        private final FloatBuilder positions = new FloatBuilder();
        private final IntBuilder primitives = new IntBuilder();
        private final OpacityMicromapData.Builder opacityMicromap;
        private int triangleCount;

        Mesh(Key key, boolean buildOpacityMicromap) {
            this.key = key;
            this.cutout = (key.flags & PrimitivePacking.FLAG_CUTOUT) != 0;
            this.transmissive =
                    (key.flags & PrimitivePacking.FLAG_TRANSMISSIVE) != 0;
            this.cutoutGeometry = this.cutout && !this.transmissive;
            this.opacityMicromap = this.cutoutGeometry && buildOpacityMicromap
                    ? new OpacityMicromapData.Builder()
                    : null;
        }

        void addTop(
                float minimumU,
                float minimumV,
                float maximumU,
                float maximumV,
                float height,
                float atlasU,
                float atlasV) {
            float[][] corners = {
                this.point(minimumU, minimumV, height),
                this.point(maximumU, minimumV, height),
                this.point(maximumU, maximumV, height),
                this.point(minimumU, maximumV, height)
            };
            float[] normal = new float[3];
            normal[this.key.planeAxis] = this.key.normalSign;
            this.addQuad(corners, normal, atlasU, atlasV);
        }

        void addWall(
                int projectedAxis,
                float plane,
                float minimumAlong,
                float maximumAlong,
                float minimumHeight,
                float maximumHeight,
                int outwardSign,
                float atlasU,
                float atlasV) {
            if (!(maximumHeight > minimumHeight)) {
                return;
            }
            float[][] corners;
            if (projectedAxis == 0) {
                corners = new float[][] {
                    this.point(plane, minimumAlong, minimumHeight),
                    this.point(plane, maximumAlong, minimumHeight),
                    this.point(plane, maximumAlong, maximumHeight),
                    this.point(plane, minimumAlong, maximumHeight)
                };
            } else {
                corners = new float[][] {
                    this.point(minimumAlong, plane, minimumHeight),
                    this.point(maximumAlong, plane, minimumHeight),
                    this.point(maximumAlong, plane, maximumHeight),
                    this.point(minimumAlong, plane, maximumHeight)
                };
            }
            float[] normal = new float[3];
            int axis = projectedAxis == 0
                    ? MergeFace.projectedAxisU(this.key.planeAxis)
                    : MergeFace.projectedAxisV(this.key.planeAxis);
            normal[axis] = outwardSign;
            this.addQuad(corners, normal, atlasU, atlasV);
        }

        private float[] point(float u, float v, float height) {
            float[] result = new float[3];
            result[this.key.planeAxis] =
                    height == 0.0F ? 0.0F : this.key.normalSign * height;
            result[MergeFace.projectedAxisU(this.key.planeAxis)] = u;
            result[MergeFace.projectedAxisV(this.key.planeAxis)] = v;
            return result;
        }

        private void addQuad(
                float[][] corners, float[] outward, float atlasU, float atlasV) {
            float[] edgeOne = subtract(corners[1], corners[0]);
            float[] edgeTwo = subtract(corners[2], corners[0]);
            float[] cross = cross(edgeOne, edgeTwo);
            if (dot(cross, outward) < 0.0F) {
                float[] swap = corners[1];
                corners[1] = corners[3];
                corners[3] = swap;
            }
            this.addTriangle(
                    corners[0], corners[1], corners[2], outward, atlasU, atlasV);
            this.addTriangle(
                    corners[0], corners[2], corners[3], outward, atlasU, atlasV);
        }

        private void addTriangle(
                float[] first,
                float[] second,
                float[] third,
                float[] outward,
                float atlasU,
                float atlasV) {
            this.positions.add(first);
            this.positions.add(second);
            this.positions.add(third);
            int packedUv = PrimitivePacking.packHalf2(atlasU, atlasV);
            float[] edgeOne = subtract(second, first);
            float[] edgeTwo = subtract(third, first);
            int packedNormal = PrimitivePacking.packTriangleNormal(
                    edgeOne[0],
                    edgeOne[1],
                    edgeOne[2],
                    edgeTwo[0],
                    edgeTwo[1],
                    edgeTwo[2],
                    outward[0],
                    outward[1],
                    outward[2]);
            long tangent = PrimitivePacking.packTriangleTangent(
                    edgeOne[0],
                    edgeOne[1],
                    edgeOne[2],
                    edgeTwo[0],
                    edgeTwo[1],
                    edgeTwo[2],
                    0.0F,
                    0.0F,
                    0.0F,
                    0.0F,
                    packedNormal);
            int flags = this.key.flags;
            if ((tangent & 0x1_0000_0000L) != 0L
                    && (flags & PrimitivePacking.FLAG_LABPBR_NORMAL) != 0) {
                flags |= PrimitivePacking.FLAG_TANGENT_NEGATIVE;
            }
            this.primitives.add(packedUv);
            this.primitives.add(packedUv);
            this.primitives.add(packedUv);
            this.primitives.add(PrimitivePacking.packTintFlags(0x00ff_ffff, flags));
            this.primitives.add(packedNormal);
            this.primitives.add(PrimitivePacking.packFlagsEmitter(
                    flags, PrimitivePacking.NO_EMITTER_INDEX));
            this.primitives.add(Float.floatToRawIntBits(0.0F));
            this.primitives.add((int) tangent);
            if (this.cutoutGeometry) {
                if (this.opacityMicromap == null) {
                    // Retain the any-hit path when the device cannot consume opacity micromaps.
                } else {
                    this.opacityMicromap.addTriangle(
                            this.key.sprite, packedUv, packedUv, packedUv);
                }
            }
            this.triangleCount++;
        }

        CpuVoxelMesh build() {
            OpacityMicromapData opacity = !this.cutoutGeometry
                    ? OpacityMicromapData.EMPTY
                    : (this.opacityMicromap == null
                            ? OpacityMicromapData.fullyUnknown(this.triangleCount)
                            : this.opacityMicromap.build());
            return new CpuVoxelMesh(
                    this.positions.build(),
                    this.primitives.build(),
                    this.transmissive || this.cutoutGeometry ? 0 : this.triangleCount,
                    this.cutoutGeometry ? this.triangleCount : 0,
                    this.transmissive ? this.triangleCount : 0,
                    opacity);
        }
    }

    private static float unpackHalf(int packed, boolean high) {
        return Float.float16ToFloat((short) (high ? packed >>> 16 : packed));
    }

    private static float clampUnit(float value) {
        return Math.max(0.0F, Math.min(Math.nextDown(1.0F), value));
    }

    private static float[] subtract(float[] first, float[] second) {
        return new float[] {
            first[0] - second[0],
            first[1] - second[1],
            first[2] - second[2]
        };
    }

    private static float[] cross(float[] first, float[] second) {
        return new float[] {
            first[1] * second[2] - first[2] * second[1],
            first[2] * second[0] - first[0] * second[2],
            first[0] * second[1] - first[1] * second[0]
        };
    }

    private static float dot(float[] first, float[] second) {
        return first[0] * second[0]
                + first[1] * second[1]
                + first[2] * second[2];
    }

    private static final class FloatBuilder {
        private float[] values = new float[4096];
        private int size;

        void add(float[] value) {
            this.ensure(3);
            this.values[this.size++] = value[0];
            this.values[this.size++] = value[1];
            this.values[this.size++] = value[2];
        }

        float[] build() {
            return Arrays.copyOf(this.values, this.size);
        }

        private void ensure(int count) {
            if (this.size + count > this.values.length) {
                this.values = Arrays.copyOf(
                        this.values,
                        Math.max(this.values.length * 2, this.size + count));
            }
        }
    }

    private static final class IntBuilder {
        private int[] values = new int[4096];
        private int size;

        void add(int value) {
            if (this.size == this.values.length) {
                this.values = Arrays.copyOf(this.values, this.values.length * 2);
            }
            this.values[this.size++] = value;
        }

        int[] build() {
            return Arrays.copyOf(this.values, this.size);
        }
    }
}
