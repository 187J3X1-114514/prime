package dev.prime.render.terrain;

import java.util.Arrays;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Builds the renderer-owned Section payload from geometry accepted by Minecraft's mesh compiler.
 *
 * <p>This class deliberately has no knowledge of Mixins, render tasks, block states, or model
 * selection. The vanilla interpretation module supplies already-decided quads plus an independent
 * semantic sidecar; the Vulkan scene only receives the immutable {@link CpuSectionMesh} result.
 */
public final class SectionMeshAccumulator {
    private static final int[] FIRST_TRIANGLE = new int[] {0, 1, 2};
    private static final int[] SECOND_TRIANGLE = new int[] {0, 2, 3};

    private final MeshBuilder opaque = new MeshBuilder();
    private final MeshBuilder nonOpaque = new MeshBuilder();
    private final CpuSectionLights.Builder lights = new CpuSectionLights.Builder();
    private final LabPbrMaterialSet labPbrMaterials;

    public SectionMeshAccumulator(LabPbrMaterialSet labPbrMaterials) {
        this.labPbrMaterials = labPbrMaterials;
    }

    public void addQuad(Quad quad, Surface surface) {
        MeshBuilder destination = surface.nonOpaque() ? this.nonOpaque : this.opaque;
        this.emitTriangle(destination, quad, FIRST_TRIANGLE, surface);
        this.emitTriangle(destination, quad, SECOND_TRIANGLE, surface);
    }

    public CpuSectionMesh build() {
        float[] positions = concatenate(this.opaque.positions, this.nonOpaque.positions);
        int[] primitives = concatenate(this.opaque.primitives, this.nonOpaque.primitives);
        return new CpuSectionMesh(
                positions,
                primitives,
                this.opaque.triangleCount,
                this.nonOpaque.triangleCount,
                this.lights.build());
    }

    private void emitTriangle(MeshBuilder destination, Quad quad, int[] indices, Surface surface) {
        int firstIndex = indices[0];
        int secondIndex = indices[1];
        int thirdIndex = indices[2];
        float firstX = quad.x[firstIndex];
        float firstY = quad.y[firstIndex];
        float firstZ = quad.z[firstIndex];
        float secondX = quad.x[secondIndex];
        float secondY = quad.y[secondIndex];
        float secondZ = quad.z[secondIndex];
        float thirdX = quad.x[thirdIndex];
        float thirdY = quad.y[thirdIndex];
        float thirdZ = quad.z[thirdIndex];
        destination.positions.add(firstX);
        destination.positions.add(firstY);
        destination.positions.add(firstZ);
        destination.positions.add(secondX);
        destination.positions.add(secondY);
        destination.positions.add(secondZ);
        destination.positions.add(thirdX);
        destination.positions.add(thirdY);
        destination.positions.add(thirdZ);

        float uv0U = quad.u[firstIndex];
        float uv0V = quad.v[firstIndex];
        float uv1U = quad.u[secondIndex];
        float uv1V = quad.v[secondIndex];
        float uv2U = quad.u[thirdIndex];
        float uv2V = quad.v[thirdIndex];
        int packedUv0 = PrimitivePacking.packHalf2(uv0U, uv0V);
        int packedUv1 = PrimitivePacking.packHalf2(uv1U, uv1V);
        int packedUv2 = PrimitivePacking.packHalf2(uv2U, uv2V);
        int packedTint = PrimitivePacking.packTint(surface.tint());
        destination.primitives.add(packedUv0);
        destination.primitives.add(packedUv1);
        destination.primitives.add(packedUv2);
        destination.primitives.add(packedTint);

        float edge1X = secondX - firstX;
        float edge1Y = secondY - firstY;
        float edge1Z = secondZ - firstZ;
        float edge2X = thirdX - firstX;
        float edge2Y = thirdY - firstY;
        float edge2Z = thirdZ - firstZ;
        int packedUvDensity = PrimitivePacking.packUvDensity(
                edge1X,
                edge1Y,
                edge1Z,
                edge2X,
                edge2Y,
                edge2Z,
                uv1U - uv0U,
                uv1V - uv0V,
                uv2U - uv0U,
                uv2V - uv0V);
        int packedNormal = PrimitivePacking.packTriangleNormal(
                edge1X,
                edge1Y,
                edge1Z,
                edge2X,
                edge2Y,
                edge2Z,
                quad.normalX,
                quad.normalY,
                quad.normalZ);
        destination.primitives.add(packedNormal);
        long packedTangent = PrimitivePacking.packTriangleTangent(
                edge1X,
                edge1Y,
                edge1Z,
                edge2X,
                edge2Y,
                edge2Z,
                uv1U - uv0U,
                uv1V - uv0V,
                uv2U - uv0U,
                uv2V - uv0V,
                packedNormal);
        int flags = PrimitivePacking.packFlags(
                surface.cutout(),
                surface.animated(),
                surface.transmissive(),
                surface.thinWalled(),
                surface.water(),
                surface.foliage());
        flags = PrimitivePacking.withLabPbr(
                flags,
                this.labPbrMaterials.hasNormal(surface.sprite().contents().name()),
                this.labPbrMaterials.hasSpecular(surface.sprite().contents().name()),
                (packedTangent & 0x1_0000_0000L) != 0L);
        destination.primitives.add(flags);
        destination.primitives.add(this.lights.addTriangle(
                firstX,
                firstY,
                firstZ,
                secondX,
                secondY,
                secondZ,
                thirdX,
                thirdY,
                thirdZ,
                packedUv0,
                packedUv1,
                packedUv2,
                surface.tint(),
                packedTint,
                surface.cutout(),
                surface.lightEmission(),
                surface.sprite(),
                this.labPbrMaterials.emissionMap(surface.sprite().contents().name())));
        destination.primitives.add(packedUvDensity);
        destination.primitives.add((int) packedTangent);
        destination.triangleCount++;
    }

    private static float[] concatenate(FloatArrayBuilder first, FloatArrayBuilder second) {
        float[] result = Arrays.copyOf(first.values, first.size);
        int firstSize = result.length;
        result = Arrays.copyOf(result, firstSize + second.size);
        System.arraycopy(second.values, 0, result, firstSize, second.size);
        return result;
    }

    private static int[] concatenate(IntArrayBuilder first, IntArrayBuilder second) {
        int[] result = Arrays.copyOf(first.values, first.size);
        int firstSize = result.length;
        result = Arrays.copyOf(result, firstSize + second.size);
        System.arraycopy(second.values, 0, result, firstSize, second.size);
        return result;
    }

    /** Mutable quad scratch owned by one capture session and never published. */
    public static final class Quad {
        public final float[] x = new float[4];
        public final float[] y = new float[4];
        public final float[] z = new float[4];
        public final float[] u = new float[4];
        public final float[] v = new float[4];
        public float normalX;
        public float normalY;
        public float normalZ;
    }

    /** Mutable per-session scratch for semantics kept outside Minecraft's mesh interfaces. */
    public static final class Surface {
        private int tint;
        private boolean nonOpaque;
        private boolean cutout;
        private boolean animated;
        private boolean transmissive;
        private boolean thinWalled;
        private boolean water;
        private boolean foliage;
        private int lightEmission;
        private TextureAtlasSprite sprite;

        public Surface set(
                int tint,
                boolean nonOpaque,
                boolean cutout,
                boolean animated,
                boolean transmissive,
                boolean thinWalled,
                boolean water,
                boolean foliage,
                int lightEmission,
                TextureAtlasSprite sprite) {
            this.tint = tint;
            this.nonOpaque = nonOpaque;
            this.cutout = cutout;
            this.animated = animated;
            this.transmissive = transmissive;
            this.thinWalled = thinWalled;
            this.water = water;
            this.foliage = foliage;
            this.lightEmission = lightEmission;
            this.sprite = sprite;
            return this;
        }

        int tint() {
            return this.tint;
        }

        boolean nonOpaque() {
            return this.nonOpaque;
        }

        boolean cutout() {
            return this.cutout;
        }

        boolean animated() {
            return this.animated;
        }

        boolean transmissive() {
            return this.transmissive;
        }

        boolean thinWalled() {
            return this.thinWalled;
        }

        boolean water() {
            return this.water;
        }

        boolean foliage() {
            return this.foliage;
        }

        int lightEmission() {
            return this.lightEmission;
        }

        TextureAtlasSprite sprite() {
            return this.sprite;
        }
    }

    private static final class MeshBuilder {
        private final FloatArrayBuilder positions = new FloatArrayBuilder();
        private final IntArrayBuilder primitives = new IntArrayBuilder();
        private int triangleCount;
    }

    private static final class FloatArrayBuilder {
        private float[] values = new float[1024];
        private int size;

        private void add(float value) {
            if (this.size == this.values.length) {
                this.values = Arrays.copyOf(this.values, this.values.length * 2);
            }
            this.values[this.size++] = value;
        }
    }

    private static final class IntArrayBuilder {
        private int[] values = new int[1024];
        private int size;

        private void add(int value) {
            if (this.size == this.values.length) {
                this.values = Arrays.copyOf(this.values, this.values.length * 2);
            }
            this.values[this.size++] = value;
        }
    }
}
