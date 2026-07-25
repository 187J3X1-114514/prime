package dev.prime.render.terrain;

import java.util.Arrays;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * One exact axis-aligned unit face retained until the enclosing 64-block cluster is assembled.
 *
 * <p>The three UV words encode the atlas coordinates at projected corners (0,0), (1,0), and
 * (0,1). A negative UV-density word tags the primitive as periodic without expanding the
 * primitive ABI.
 */
public final class MergeFace {
    private static final int PLANE_GRID_SCALE = 16;
    private static final float POSITION_EPSILON = 1.0E-5F;
    private static final float NORMAL_EPSILON = 1.0E-4F;
    private static final float UV_EPSILON = 2.0E-5F;

    private final int planeAxis;
    private final int normalSign;
    private final int planeCell;
    private final int cellU;
    private final int cellV;
    private final TextureAtlasSprite sprite;
    private final int[] primitive;
    private final boolean cutout;
    private final boolean transmissive;
    private final boolean buildOpacityMicromap;

    private MergeFace(
            int planeAxis,
            int normalSign,
            int planeCell,
            int cellU,
            int cellV,
            TextureAtlasSprite sprite,
            int[] primitive,
            boolean cutout,
            boolean transmissive,
            boolean buildOpacityMicromap) {
        this.planeAxis = planeAxis;
        this.normalSign = normalSign;
        this.planeCell = planeCell;
        this.cellU = cellU;
        this.cellV = cellV;
        this.sprite = sprite;
        this.primitive = primitive;
        this.cutout = cutout;
        this.transmissive = transmissive;
        this.buildOpacityMicromap = buildOpacityMicromap;
    }

    static MergeFace tryCreate(
            SectionMeshAccumulator.Quad quad,
            SectionMeshAccumulator.Surface surface,
            LabPbrMaterialSet labPbrMaterials,
            boolean buildOpacityMicromap) {
        if (!surface.mergeable()
                || surface.water()
                || surface.lightEmission() != 0) {
            return null;
        }
        float[][] coordinates = {quad.x, quad.y, quad.z};
        float[] minimum = new float[3];
        float[] maximum = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            minimum[axis] = coordinates[axis][0];
            maximum[axis] = coordinates[axis][0];
            for (int vertex = 1; vertex < 4; vertex++) {
                minimum[axis] = Math.min(minimum[axis], coordinates[axis][vertex]);
                maximum[axis] = Math.max(maximum[axis], coordinates[axis][vertex]);
            }
        }
        int planeAxis = -1;
        for (int axis = 0; axis < 3; axis++) {
            if (maximum[axis] - minimum[axis] <= POSITION_EPSILON) {
                if (planeAxis != -1) {
                    return null;
                }
                planeAxis = axis;
            }
        }
        if (planeAxis == -1) {
            return null;
        }
        int planeCell = Math.round(minimum[planeAxis] * PLANE_GRID_SCALE);
        float snappedPlane = planeCell / (float) PLANE_GRID_SCALE;
        if (!near(minimum[planeAxis], snappedPlane, POSITION_EPSILON)) {
            return null;
        }
        int axisU = projectedAxisU(planeAxis);
        int axisV = projectedAxisV(planeAxis);
        if (!unitGridSpan(minimum[axisU], maximum[axisU])
                || !unitGridSpan(minimum[axisV], maximum[axisV])) {
            return null;
        }

        float[] normal = {quad.normalX, quad.normalY, quad.normalZ};
        if (Math.abs(Math.abs(normal[planeAxis]) - 1.0F) > NORMAL_EPSILON
                || Math.abs(normal[axisU]) > NORMAL_EPSILON
                || Math.abs(normal[axisV]) > NORMAL_EPSILON) {
            return null;
        }
        int normalSign = normal[planeAxis] < 0.0F ? -1 : 1;
        int[] cornerVertex = {-1, -1, -1, -1};
        for (int vertex = 0; vertex < 4; vertex++) {
            int highU = corner(
                    coordinates[axisU][vertex], minimum[axisU], maximum[axisU]);
            int highV = corner(
                    coordinates[axisV][vertex], minimum[axisV], maximum[axisV]);
            if (highU < 0 || highV < 0) {
                return null;
            }
            int corner = highU | highV << 1;
            if (cornerVertex[corner] != -1) {
                return null;
            }
            cornerVertex[corner] = vertex;
        }

        int corner00 = cornerVertex[0];
        int corner10 = cornerVertex[1];
        int corner01 = cornerVertex[2];
        int corner11 = cornerVertex[3];
        if (!near(
                        quad.u[corner11],
                        quad.u[corner10] + quad.u[corner01] - quad.u[corner00],
                        UV_EPSILON)
                || !near(
                        quad.v[corner11],
                        quad.v[corner10] + quad.v[corner01] - quad.v[corner00],
                        UV_EPSILON)) {
            return null;
        }

        int packedUv0 = PrimitivePacking.packHalf2(quad.u[corner00], quad.v[corner00]);
        int packedUv1 = PrimitivePacking.packHalf2(quad.u[corner10], quad.v[corner10]);
        int packedUv2 = PrimitivePacking.packHalf2(quad.u[corner01], quad.v[corner01]);
        float edgeUX = axisU == 0 ? 1.0F : 0.0F;
        float edgeUY = axisU == 1 ? 1.0F : 0.0F;
        float edgeUZ = axisU == 2 ? 1.0F : 0.0F;
        float edgeVX = axisV == 0 ? 1.0F : 0.0F;
        float edgeVY = axisV == 1 ? 1.0F : 0.0F;
        float edgeVZ = axisV == 2 ? 1.0F : 0.0F;
        float deltaU1 = quad.u[corner10] - quad.u[corner00];
        float deltaV1 = quad.v[corner10] - quad.v[corner00];
        float deltaU2 = quad.u[corner01] - quad.u[corner00];
        float deltaV2 = quad.v[corner01] - quad.v[corner00];
        int packedNormal = PrimitivePacking.packTriangleNormal(
                edgeUX,
                edgeUY,
                edgeUZ,
                edgeVX,
                edgeVY,
                edgeVZ,
                quad.normalX,
                quad.normalY,
                quad.normalZ);
        long packedTangent = PrimitivePacking.packTriangleTangent(
                edgeUX,
                edgeUY,
                edgeUZ,
                edgeVX,
                edgeVY,
                edgeVZ,
                deltaU1,
                deltaV1,
                deltaU2,
                deltaV2,
                packedNormal);
        int flags = PrimitivePacking.packFlags(
                surface.cutout(),
                surface.animated(),
                surface.transmissive(),
                surface.thinWalled(),
                false,
                surface.foliage());
        flags = PrimitivePacking.withLabPbr(
                flags,
                labPbrMaterials.hasNormal(surface.sprite().contents().name()),
                labPbrMaterials.hasSpecular(surface.sprite().contents().name()),
                (packedTangent & 0x1_0000_0000L) != 0L);
        int density = PrimitivePacking.packUvDensity(
                edgeUX,
                edgeUY,
                edgeUZ,
                edgeVX,
                edgeVY,
                edgeVZ,
                deltaU1,
                deltaV1,
                deltaU2,
                deltaV2);
        if (!(Float.intBitsToFloat(density) > 0.0F)) {
            return null;
        }
        int[] primitive = {
            packedUv0,
            packedUv1,
            packedUv2,
            PrimitivePacking.packTintFlags(PrimitivePacking.packTint(surface.tint()), flags),
            packedNormal,
            PrimitivePacking.packFlagsEmitter(flags, PrimitivePacking.NO_EMITTER_INDEX),
            density | Integer.MIN_VALUE,
            (int) packedTangent
        };
        return new MergeFace(
                planeAxis,
                normalSign,
                planeCell,
                Math.round(minimum[axisU]),
                Math.round(minimum[axisV]),
                surface.sprite(),
                primitive,
                surface.cutout(),
                surface.transmissive(),
                buildOpacityMicromap);
    }

    MergeFace translated(int x, int y, int z) {
        int[] translation = {x, y, z};
        return new MergeFace(
                this.planeAxis,
                this.normalSign,
                this.planeCell + translation[this.planeAxis] * PLANE_GRID_SCALE,
                this.cellU + translation[projectedAxisU(this.planeAxis)],
                this.cellV + translation[projectedAxisV(this.planeAxis)],
                this.sprite,
                this.primitive,
                this.cutout,
                this.transmissive,
                this.buildOpacityMicromap);
    }

    int planeAxis() {
        return this.planeAxis;
    }

    int normalSign() {
        return this.normalSign;
    }

    float plane() {
        return this.planeCell / (float) PLANE_GRID_SCALE;
    }

    int planeCell() {
        return this.planeCell;
    }

    int cellU() {
        return this.cellU;
    }

    int cellV() {
        return this.cellV;
    }

    TextureAtlasSprite sprite() {
        return this.sprite;
    }

    int[] primitive() {
        return this.primitive;
    }

    boolean cutout() {
        return this.cutout;
    }

    boolean transmissive() {
        return this.transmissive;
    }

    boolean buildOpacityMicromap() {
        return this.buildOpacityMicromap;
    }

    boolean sameMaterial(MergeFace other) {
        return this.sprite == other.sprite
                && this.cutout == other.cutout
                && this.transmissive == other.transmissive
                && this.buildOpacityMicromap == other.buildOpacityMicromap
                && Arrays.equals(this.primitive, other.primitive);
    }

    int materialHash() {
        int hash = System.identityHashCode(this.sprite);
        hash = 31 * hash + Boolean.hashCode(this.cutout);
        hash = 31 * hash + Boolean.hashCode(this.transmissive);
        hash = 31 * hash + Boolean.hashCode(this.buildOpacityMicromap);
        return 31 * hash + Arrays.hashCode(this.primitive);
    }

    static int projectedAxisU(int planeAxis) {
        return planeAxis == 0 ? 1 : 0;
    }

    static int projectedAxisV(int planeAxis) {
        return planeAxis == 2 ? 1 : 2;
    }

    private static boolean unitGridSpan(float minimum, float maximum) {
        return near(minimum, Math.round(minimum), POSITION_EPSILON)
                && near(maximum, Math.round(maximum), POSITION_EPSILON)
                && near(maximum - minimum, 1.0F, POSITION_EPSILON);
    }

    private static int corner(float value, float minimum, float maximum) {
        if (near(value, minimum, POSITION_EPSILON)) {
            return 0;
        }
        return near(value, maximum, POSITION_EPSILON) ? 1 : -1;
    }

    private static boolean near(float first, float second, float epsilon) {
        return Math.abs(first - second) <= epsilon;
    }
}
