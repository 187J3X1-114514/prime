package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSprite;
import java.util.Arrays;

/**
 * One exact axis-aligned unit face retained until the enclosing 64-block cluster is assembled.
 *
 * <p>The three UV words encode the atlas coordinates at projected corners (0,0), (1,0), and
 * (0,1). Exact float coordinates are retained alongside the fixed-point atlas UVs for merge
 * compatibility and CPU material translation. A negative UV-density word normally tags
 * the primitive as periodic. Raster composites instead use that word for an exact signed atlas
 * pixel offset under an explicit primitive flag.
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
    private final CapturedSprite sprite;
    private final int[] primitive;
    private final float uv0U;
    private final float uv0V;
    private final float uv1U;
    private final float uv1V;
    private final float uv2U;
    private final float uv2V;
    private final boolean cutout;
    private final boolean transmissive;
    private final boolean rasterOverlay;
    private final boolean buildOpacityMicromap;
    private final LabPbrHeightMap labPbrHeightMap;
    private final LabPbrMaterialMap labPbrMaterialMap;

    private MergeFace(
            int planeAxis,
            int normalSign,
            int planeCell,
            int cellU,
            int cellV,
            CapturedSprite sprite,
            int[] primitive,
            float uv0U,
            float uv0V,
            float uv1U,
            float uv1V,
            float uv2U,
            float uv2V,
            boolean cutout,
            boolean transmissive,
            boolean rasterOverlay,
            boolean buildOpacityMicromap,
            LabPbrHeightMap labPbrHeightMap,
            LabPbrMaterialMap labPbrMaterialMap) {
        this.planeAxis = planeAxis;
        this.normalSign = normalSign;
        this.planeCell = planeCell;
        this.cellU = cellU;
        this.cellV = cellV;
        this.sprite = sprite;
        this.primitive = primitive;
        this.uv0U = uv0U;
        this.uv0V = uv0V;
        this.uv1U = uv1U;
        this.uv1V = uv1V;
        this.uv2U = uv2U;
        this.uv2V = uv2V;
        this.cutout = cutout;
        this.transmissive = transmissive;
        this.rasterOverlay = rasterOverlay;
        this.buildOpacityMicromap = buildOpacityMicromap;
        this.labPbrHeightMap = labPbrHeightMap;
        this.labPbrMaterialMap = labPbrMaterialMap;
    }

    static MergeFace tryCreate(
            SectionMeshAccumulator.Quad quad,
            SectionMeshAccumulator.Surface surface,
            LabPbrMaterialSet labPbrMaterials,
            boolean buildOpacityMicromap) {
        LabPbrEmissionMap emission =
                labPbrMaterials.emissionMap(surface.sprite().id());
        if (!surface.mergeable()
                || surface.hasSurfaceRelation()
                || surface.water()
                || surface.lightEmission() != 0
                || (emission != null && emission.hasPositiveEmission())) {
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

        int packedUv0 = PrimitivePacking.packUv(quad.u[corner00], quad.v[corner00]);
        int packedUv1 = PrimitivePacking.packUv(quad.u[corner10], quad.v[corner10]);
        int packedUv2 = PrimitivePacking.packUv(quad.u[corner01], quad.v[corner01]);
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
        int flags = PrimitivePacking.encode(MaterialRecipeResolver.resolve(
                surface.sprite(),
                surface.builtinMaterialClass(),
                surface.animated(),
                false,
                surface.foliage(),
                labPbrMaterials,
                surface.cutout(),
                surface.transmissive(),
                surface.thinWalled(),
                (packedTangent & 0x1_0000_0000L) != 0L,
                false,
                false));
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
            PrimitivePacking.packTintControl(PrimitivePacking.packTint(surface.tint()), flags),
            packedNormal,
            PrimitivePacking.packControlEmitter(flags, PrimitivePacking.NO_EMITTER_INDEX),
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
                quad.u[corner00],
                quad.v[corner00],
                quad.u[corner10],
                quad.v[corner10],
                quad.u[corner01],
                quad.v[corner01],
                surface.cutout(),
                surface.transmissive(),
                surface.rasterOverlay(),
                buildOpacityMicromap,
                labPbrMaterials.heightMap(surface.sprite().id()),
                labPbrMaterials.materialMap(surface.sprite().id()));
    }

    static MergeFace tryComposite(MergeFace base, MergeFace overlay) {
        if (base.planeAxis != overlay.planeAxis
                || base.normalSign != overlay.normalSign
                || base.planeCell != overlay.planeCell
                || base.cellU != overlay.cellU
                || base.cellV != overlay.cellV) {
            throw new IllegalArgumentException(
                    "Raster material layers are not coincident");
        }
        int baseFlags = PrimitivePacking.unpackControl(
                base.primitive[3], base.primitive[5]);
        int overlayFlags = PrimitivePacking.unpackControl(
                overlay.primitive[3], overlay.primitive[5]);
        if ((baseFlags
                                & (PrimitivePacking.CONTROL_ALPHA_CUTOUT
                                        | PrimitivePacking.CONTROL_ANIMATED
                                        | PrimitivePacking.CONTROL_SCATTERING_MASK
                                        | PrimitivePacking.CONTROL_WATER_MEDIUM
                                        | PrimitivePacking.CONTROL_FRONT_FACE_ONLY
                                        | PrimitivePacking.CONTROL_RASTER_COMPOSITE))
                        != 0
                || (overlayFlags & PrimitivePacking.CONTROL_ALPHA_CUTOUT) == 0
                || ((overlayFlags
                                & (PrimitivePacking.CONTROL_ANIMATED
                                        | PrimitivePacking.CONTROL_SCATTERING_MASK
                                        | PrimitivePacking.CONTROL_WATER_MEDIUM
                                        | PrimitivePacking.CONTROL_FRONT_FACE_ONLY
                                        | PrimitivePacking.CONTROL_RASTER_COMPOSITE))
                        != 0)
                || (PrimitivePacking.materialRecipeControl(baseFlags)
                                & ~PrimitivePacking.CONTROL_ALPHA_CUTOUT)
                        != (PrimitivePacking.materialRecipeControl(overlayFlags)
                                & ~PrimitivePacking.CONTROL_ALPHA_CUTOUT)
                || base.sprite.animated()
                || overlay.sprite.animated()) {
            return null;
        }

        float deltaU = overlay.uv0U - base.uv0U;
        float deltaV = overlay.uv0V - base.uv0V;
        if (!near(overlay.uv1U - base.uv1U, deltaU, UV_EPSILON)
                || !near(overlay.uv2U - base.uv2U, deltaU, UV_EPSILON)
                || !near(overlay.uv1V - base.uv1V, deltaV, UV_EPSILON)
                || !near(overlay.uv2V - base.uv2V, deltaV, UV_EPSILON)) {
            return null;
        }
        int atlasWidth = commonAtlasExtent(
                base.sprite.frameWidth(),
                base.sprite.u1() - base.sprite.u0(),
                overlay.sprite.frameWidth(),
                overlay.sprite.u1() - overlay.sprite.u0());
        int atlasHeight = commonAtlasExtent(
                base.sprite.frameHeight(),
                base.sprite.v1() - base.sprite.v0(),
                overlay.sprite.frameHeight(),
                overlay.sprite.v1() - overlay.sprite.v0());
        int pixelOffsetU = exactPixelOffset(deltaU, atlasWidth);
        int pixelOffsetV = exactPixelOffset(deltaV, atlasHeight);
        if (pixelOffsetU < Short.MIN_VALUE
                || pixelOffsetU > Short.MAX_VALUE
                || pixelOffsetV < Short.MIN_VALUE
                || pixelOffsetV > Short.MAX_VALUE) {
            return null;
        }

        int flags = baseFlags | PrimitivePacking.CONTROL_RASTER_COMPOSITE;
        PrimitivePacking.requireValidControl(flags);
        int[] primitive = base.primitive.clone();
        primitive[3] = PrimitivePacking.packTintControl(
                primitive[3], flags);
        primitive[5] = PrimitivePacking.packRasterCompositeControl(
                flags, overlay.primitive[3]);
        primitive[6] = pixelOffsetU & 0xffff
                | (pixelOffsetV & 0xffff) << 16;
        return new MergeFace(
                base.planeAxis,
                base.normalSign,
                base.planeCell,
                base.cellU,
                base.cellV,
                base.sprite,
                primitive,
                base.uv0U,
                base.uv0V,
                base.uv1U,
                base.uv1V,
                base.uv2U,
                base.uv2V,
                false,
                false,
                false,
                false,
                base.labPbrHeightMap,
                base.labPbrMaterialMap);
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
                this.uv0U,
                this.uv0V,
                this.uv1U,
                this.uv1V,
                this.uv2U,
                this.uv2V,
                this.cutout,
                this.transmissive,
                this.rasterOverlay,
                this.buildOpacityMicromap,
                this.labPbrHeightMap,
                this.labPbrMaterialMap);
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

    CapturedSprite sprite() {
        return this.sprite;
    }

    int[] primitive() {
        return this.primitive;
    }

    float uv0U() {
        return this.uv0U;
    }

    float uv0V() {
        return this.uv0V;
    }

    float uv1U() {
        return this.uv1U;
    }

    float uv1V() {
        return this.uv1V;
    }

    float uv2U() {
        return this.uv2U;
    }

    float uv2V() {
        return this.uv2V;
    }

    boolean cutout() {
        return this.cutout;
    }

    boolean transmissive() {
        return this.transmissive;
    }

    boolean rasterOverlay() {
        return this.rasterOverlay;
    }

    boolean buildOpacityMicromap() {
        return this.buildOpacityMicromap;
    }

    boolean frontFaceOnly() {
        return (PrimitivePacking.unpackControl(
                        this.primitive[3], this.primitive[5])
                        & PrimitivePacking.CONTROL_FRONT_FACE_ONLY)
                != 0;
    }

    LabPbrHeightMap labPbrHeightMap() {
        return this.labPbrHeightMap;
    }

    LabPbrMaterialMap labPbrMaterialMap() {
        return this.labPbrMaterialMap;
    }

    boolean sameMaterial(MergeFace other) {
        return this.sprite.equals(other.sprite)
                && this.cutout == other.cutout
                && this.transmissive == other.transmissive
                && this.buildOpacityMicromap == other.buildOpacityMicromap
                && Float.floatToIntBits(this.uv0U) == Float.floatToIntBits(other.uv0U)
                && Float.floatToIntBits(this.uv0V) == Float.floatToIntBits(other.uv0V)
                && Float.floatToIntBits(this.uv1U) == Float.floatToIntBits(other.uv1U)
                && Float.floatToIntBits(this.uv1V) == Float.floatToIntBits(other.uv1V)
                && Float.floatToIntBits(this.uv2U) == Float.floatToIntBits(other.uv2U)
                && Float.floatToIntBits(this.uv2V) == Float.floatToIntBits(other.uv2V)
                && Arrays.equals(this.primitive, other.primitive);
    }

    int materialHash() {
        int hash = this.sprite.hashCode();
        hash = 31 * hash + Boolean.hashCode(this.cutout);
        hash = 31 * hash + Boolean.hashCode(this.transmissive);
        hash = 31 * hash + Boolean.hashCode(this.buildOpacityMicromap);
        hash = 31 * hash + Float.hashCode(this.uv0U);
        hash = 31 * hash + Float.hashCode(this.uv0V);
        hash = 31 * hash + Float.hashCode(this.uv1U);
        hash = 31 * hash + Float.hashCode(this.uv1V);
        hash = 31 * hash + Float.hashCode(this.uv2U);
        hash = 31 * hash + Float.hashCode(this.uv2V);
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

    private static int commonAtlasExtent(
            int firstPixels,
            float firstSpan,
            int secondPixels,
            float secondSpan) {
        if (!(Math.abs(firstSpan) > 1.0e-12F)
                || !(Math.abs(secondSpan) > 1.0e-12F)) {
            return -1;
        }
        int firstExtent = Math.round(firstPixels / Math.abs(firstSpan));
        int secondExtent = Math.round(secondPixels / Math.abs(secondSpan));
        return firstExtent > 0
                        && firstExtent == secondExtent
                        && near(
                                Math.abs(firstSpan),
                                firstPixels / (float) firstExtent,
                                atlasTolerance(firstExtent))
                        && near(
                                Math.abs(secondSpan),
                                secondPixels / (float) secondExtent,
                                atlasTolerance(secondExtent))
                ? firstExtent
                : -1;
    }

    private static int exactPixelOffset(float delta, int atlasExtent) {
        if (atlasExtent <= 0 || !Float.isFinite(delta)) {
            return Integer.MAX_VALUE;
        }
        int pixels = Math.round(delta * atlasExtent);
        return near(
                        delta,
                        pixels / (float) atlasExtent,
                        atlasTolerance(atlasExtent))
                ? pixels
                : Integer.MAX_VALUE;
    }

    private static float atlasTolerance(int atlasExtent) {
        return Math.min(UV_EPSILON, 0.25F / atlasExtent);
    }
}
