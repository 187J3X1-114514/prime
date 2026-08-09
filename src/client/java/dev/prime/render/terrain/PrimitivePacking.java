package dev.prime.render.terrain;

import dev.prime.render.material.BuiltinMaterialClass;
import dev.prime.render.material.CoverageMode;
import dev.prime.render.material.MaterialDetail;
import dev.prime.render.material.MaterialRecipe;
import dev.prime.render.material.MediumHint;
import dev.prime.render.material.PrimitiveControl;
import dev.prime.render.material.ScatteringFamily;

public final class PrimitivePacking {
    private static final float UV_FIXED_SCALE = 65_536.0F;
    private static final int UV_FIXED_ONE = 0xffff;
    public static final int CONTROL_ALPHA_CUTOUT = 1;
    public static final int CONTROL_ANIMATED = 1 << 1;
    public static final int CONTROL_SCATTERING_SHIFT = 2;
    public static final int CONTROL_SCATTERING_MASK = 3 << CONTROL_SCATTERING_SHIFT;
    public static final int CONTROL_OPAQUE = 0;
    public static final int CONTROL_DIELECTRIC_SOLID = 1 << CONTROL_SCATTERING_SHIFT;
    public static final int CONTROL_DIELECTRIC_THIN = 2 << CONTROL_SCATTERING_SHIFT;
    public static final int CONTROL_FOLIAGE_THIN = 3 << CONTROL_SCATTERING_SHIFT;
    public static final int CONTROL_WATER_MEDIUM = 1 << 4;
    public static final int CONTROL_NORMAL_TEXTURE = 1 << 5;
    public static final int CONTROL_OPTICAL_TEXTURE = 1 << 6;
    public static final int CONTROL_TANGENT_NEGATIVE = 1 << 7;
    /** Accept only the authored winding's front side during any-hit traversal. */
    public static final int CONTROL_FRONT_FACE_ONLY = 1 << 8;
    /** Resolve a static base/overlay atlas pair as one material evaluation. */
    public static final int CONTROL_RASTER_COMPOSITE = 1 << 9;
    private static final int CONTROL_RESERVED = 1 << 10;
    public static final int CONTROL_BUILTIN_SHIFT = 11;
    public static final int CONTROL_BUILTIN_MASK = 15 << CONTROL_BUILTIN_SHIFT;
    public static final int CONTROL_MASK = (1 << 15) - 1;
    public static final int MATERIAL_RECIPE_MASK = 0xff | CONTROL_BUILTIN_MASK;
    public static final int DYNAMIC_TEXTURE_FLAG = 1 << 31;
    public static final int VISIBLE_EMISSION_FLAG = 1 << 10;
    public static final int DYNAMIC_RED_ALPHA_FLAG = 1 << 9;
    public static final int DYNAMIC_TEXTURE_INDEX_MASK = 63;
    public static final int NO_EMITTER_INDEX = -1;
    public static final int MAX_EMITTER_INDEX = (1 << 24) - 2;
    /**
     * Negative zero tags a constant UV stored as two full-precision floats in uv0 and uv1.
     * Ordinary negative densities remain the periodic macro-face encoding.
     */
    public static final int CONSTANT_UV_DENSITY = Float.floatToRawIntBits(-0.0F);
    /** Baked primitives keep their own color instead of inheriting the voxel instance tint. */
    public static final int CONSTANT_UV_OWN_TINT = 1;
    /** uv0/uv1 contain baked LabPBR texels instead of atlas coordinates. */
    public static final int CONSTANT_UV_BAKED_MATERIAL = 1 << 1;
    public static final int CONSTANT_UV_MODE_MASK =
            CONSTANT_UV_OWN_TINT | CONSTANT_UV_BAKED_MATERIAL;

    private PrimitivePacking() {
    }

    /** Packs geometry controls, the preset class, and a local light-emitter index. */
    public static int packControlEmitter(int control, int emitterIndex) {
        requireValidControl(control);
        if ((control & CONTROL_RASTER_COMPOSITE) != 0) {
            throw new IllegalArgumentException(
                    "Raster composite payload cannot carry an emitter");
        }
        if (emitterIndex < NO_EMITTER_INDEX || emitterIndex > MAX_EMITTER_INDEX) {
            throw new IllegalArgumentException("Primitive emitter index exceeds its 24-bit ABI field");
        }
        int encodedEmitter = emitterIndex == NO_EMITTER_INDEX ? 0 : emitterIndex + 1;
        return physicalControl(control) | encodedEmitter << 3;
    }

    public static int packTintControl(int packedTint, int control) {
        requireValidControl(control);
        return (packedTint & 0x00ff_ffff) | (control & 0xff) << 24;
    }

    public static int unpackControl(int packedTint, int packedFlagsEmitter) {
        return packedTint >>> 24
                | (packedFlagsEmitter & 7) << 8
                | (packedFlagsEmitter >>> 16 & CONTROL_BUILTIN_MASK);
    }

    public static int unpackEmitterIndex(int packed) {
        if ((packed & DYNAMIC_TEXTURE_FLAG) != 0) {
            return NO_EMITTER_INDEX;
        }
        if ((packed & (CONTROL_RASTER_COMPOSITE >>> 8)) != 0) {
            return NO_EMITTER_INDEX;
        }
        int encoded = packed >>> 3 & 0x00ff_ffff;
        return encoded == 0 ? NO_EMITTER_INDEX : encoded - 1;
    }

    public static int withEmitterIndex(int packed, int emitterIndex) {
        if ((packed & (CONTROL_RASTER_COMPOSITE >>> 8)) != 0) {
            throw new IllegalArgumentException(
                    "Raster composite payload cannot carry an emitter");
        }
        int control = (packed & 7) << 8
                | (packed >>> 16 & CONTROL_BUILTIN_MASK);
        return packControlEmitter(control, emitterIndex);
    }

    /**
     * Encodes a renderer-owned texture without assigning a light-tree emitter.
     *
     * <p>Dynamic geometry never carries a local emitter index. Its otherwise-unused high payload
     * bits select a scene texture and optionally mark directly visible emission.
     */
    public static int packDynamicControl(
            int control, int textureIndex, boolean visibleEmission) {
        return packDynamicControl(control, textureIndex, visibleEmission, false);
    }

    public static int packDynamicControl(
            int control,
            int textureIndex,
            boolean visibleEmission,
            boolean redAlpha) {
        requireValidControl(control);
        if (textureIndex < 0 || textureIndex > DYNAMIC_TEXTURE_INDEX_MASK) {
            throw new IllegalArgumentException("Dynamic texture index exceeds its ABI field");
        }
        if ((control & CONTROL_RASTER_COMPOSITE) != 0) {
            throw new IllegalArgumentException(
                    "Dynamic textures cannot use a static raster composite");
        }
        if (redAlpha && textureIndex == 0) {
            throw new IllegalArgumentException(
                    "Red-channel coverage requires a dynamic texture");
        }
        return physicalControl(control)
                | textureIndex << 3
                | (visibleEmission ? VISIBLE_EMISSION_FLAG : 0)
                | (redAlpha ? DYNAMIC_RED_ALPHA_FLAG : 0)
                | DYNAMIC_TEXTURE_FLAG;
    }

    public static int unpackDynamicTextureIndex(int packed) {
        return (packed & DYNAMIC_TEXTURE_FLAG) == 0
                ? 0
                : packed >>> 3 & DYNAMIC_TEXTURE_INDEX_MASK;
    }

    public static boolean hasVisibleEmission(int packed) {
        return (packed & (DYNAMIC_TEXTURE_FLAG | VISIBLE_EMISSION_FLAG))
                == (DYNAMIC_TEXTURE_FLAG | VISIBLE_EMISSION_FLAG);
    }

    public static int packHalf2(float x, float y) {
        int low = Float.floatToFloat16(x) & 0xffff;
        int high = Float.floatToFloat16(y) & 0xffff;
        return low | high << 16;
    }

    public static boolean usesDynamicRedAlpha(int packed) {
        return (packed & (DYNAMIC_TEXTURE_FLAG | DYNAMIC_RED_ALPHA_FLAG))
                == (DYNAMIC_TEXTURE_FLAG | DYNAMIC_RED_ALPHA_FLAG);
    }

    /**
     * Packs normalized atlas coordinates as UQ0.16, reserving {@code 0xffff} for the inclusive
     * endpoint. Power-of-two atlas texel boundaries remain exact up to 32,768 pixels.
     */
    public static int packUv(float u, float v) {
        return packUv(u) | packUv(v) << 16;
    }

    public static float unpackUv(int packed, boolean high) {
        int fixed = high ? packed >>> 16 : packed & 0xffff;
        return fixed == UV_FIXED_ONE ? 1.0F : fixed / UV_FIXED_SCALE;
    }

    static int upgradeHalfUv(int packed) {
        return packUv(
                Float.float16ToFloat((short) packed),
                Float.float16ToFloat((short) (packed >>> 16)));
    }

    private static int packUv(float coordinate) {
        if (!(coordinate >= 0.0F && coordinate <= 1.0F)
                || !Float.isFinite(coordinate)) {
            throw new IllegalArgumentException(
                    "Atlas UV must be finite and normalized");
        }
        if (coordinate == 1.0F) {
            return UV_FIXED_ONE;
        }
        return Math.min(Math.round(coordinate * UV_FIXED_SCALE), UV_FIXED_ONE - 1);
    }

    public static int packConstantUv(float coordinate) {
        if (!(coordinate >= 0.0F && coordinate <= 1.0F)
                || !Float.isFinite(coordinate)) {
            throw new IllegalArgumentException(
                    "Constant atlas UV must be finite and normalized");
        }
        return Float.floatToRawIntBits(coordinate);
    }

    public static int packTint(int argb) {
        int alpha = argb >>> 24;
        int red = argb >>> 16 & 0xff;
        int green = argb >>> 8 & 0xff;
        int blue = argb & 0xff;
        return red | green << 8 | blue << 16 | alpha << 24;
    }

    public static int encode(PrimitiveControl value) {
        MaterialRecipe material = value.material();
        int control = (material.coverage() == CoverageMode.ALPHA_CUTOUT
                        ? CONTROL_ALPHA_CUTOUT
                        : 0)
                | (value.animated() ? CONTROL_ANIMATED : 0)
                | material.scattering().encoded() << CONTROL_SCATTERING_SHIFT
                | (material.medium() == MediumHint.WATER ? CONTROL_WATER_MEDIUM : 0)
                | (material.hasDetail(MaterialDetail.NORMAL_TEXTURE)
                        ? CONTROL_NORMAL_TEXTURE
                        : 0)
                | (material.hasDetail(MaterialDetail.OPTICAL_TEXTURE)
                        ? CONTROL_OPTICAL_TEXTURE
                        : 0)
                | (value.tangentNegative() ? CONTROL_TANGENT_NEGATIVE : 0)
                | (value.frontFaceOnly() ? CONTROL_FRONT_FACE_ONLY : 0)
                | (value.rasterComposite() ? CONTROL_RASTER_COMPOSITE : 0)
                | material.builtinClass().id() << CONTROL_BUILTIN_SHIFT;
        requireValidControl(control);
        return control;
    }

    public static PrimitiveControl decode(int control) {
        requireValidControl(control);
        ScatteringFamily scattering = ScatteringFamily.fromEncoded(
                control >>> CONTROL_SCATTERING_SHIFT & 3);
        MediumHint medium = (control & CONTROL_WATER_MEDIUM) != 0
                ? MediumHint.WATER
                : scattering == ScatteringFamily.DIELECTRIC_SOLID
                                || scattering == ScatteringFamily.DIELECTRIC_THIN
                        ? MediumHint.GLASS
                        : MediumHint.NONE;
        int details = ((control & CONTROL_NORMAL_TEXTURE) != 0
                        ? MaterialDetail.NORMAL_TEXTURE.bit()
                        : 0)
                | ((control & CONTROL_OPTICAL_TEXTURE) != 0
                        ? MaterialDetail.OPTICAL_TEXTURE.bit()
                        : 0);
        MaterialRecipe recipe = new MaterialRecipe(
                (control & CONTROL_ALPHA_CUTOUT) != 0
                        ? CoverageMode.ALPHA_CUTOUT
                        : CoverageMode.OPAQUE,
                scattering,
                medium,
                details,
                BuiltinMaterialClass.fromId(
                        control >>> CONTROL_BUILTIN_SHIFT & 15));
        return new PrimitiveControl(
                recipe,
                (control & CONTROL_ANIMATED) != 0,
                (control & CONTROL_TANGENT_NEGATIVE) != 0,
                (control & CONTROL_FRONT_FACE_ONLY) != 0,
                (control & CONTROL_RASTER_COMPOSITE) != 0);
    }

    public static int materialRecipeControl(int control) {
        requireValidControl(control);
        return control & MATERIAL_RECIPE_MASK;
    }

    public static boolean isCutout(int control) {
        return (control & CONTROL_ALPHA_CUTOUT) != 0;
    }

    public static boolean isTransmissive(int control) {
        int scattering = control & CONTROL_SCATTERING_MASK;
        return scattering == ScatteringFamily.DIELECTRIC_SOLID.encoded()
                        << CONTROL_SCATTERING_SHIFT
                || scattering == ScatteringFamily.DIELECTRIC_THIN.encoded()
                        << CONTROL_SCATTERING_SHIFT;
    }

    public static boolean isFoliage(int control) {
        return (control & CONTROL_SCATTERING_MASK)
                == ScatteringFamily.FOLIAGE_THIN.encoded() << CONTROL_SCATTERING_SHIFT;
    }

    public static boolean isThinWalled(int control) {
        int scattering = control & CONTROL_SCATTERING_MASK;
        return scattering == CONTROL_DIELECTRIC_THIN
                || scattering == CONTROL_FOLIAGE_THIN;
    }

    public static int encodeLegacySemantics(
            boolean cutout,
            boolean animatedTexture,
            boolean transmissive,
            boolean thinWalled,
            boolean water,
            boolean foliage) {
        ScatteringFamily scattering = foliage
                ? ScatteringFamily.FOLIAGE_THIN
                : transmissive
                        ? thinWalled
                                ? ScatteringFamily.DIELECTRIC_THIN
                                : ScatteringFamily.DIELECTRIC_SOLID
                        : ScatteringFamily.OPAQUE;
        MediumHint medium = water
                ? MediumHint.WATER
                : transmissive ? MediumHint.GLASS : MediumHint.NONE;
        return encode(new PrimitiveControl(
                new MaterialRecipe(
                        cutout ? CoverageMode.ALPHA_CUTOUT : CoverageMode.OPAQUE,
                        scattering,
                        medium,
                        0,
                        BuiltinMaterialClass.DEFAULT),
                animatedTexture,
                false,
                false,
                false));
    }

    static void requireValidControl(int control) {
        if ((control & ~CONTROL_MASK) != 0 || (control & CONTROL_RESERVED) != 0) {
            throw new IllegalArgumentException("Primitive control contains reserved ABI bits");
        }
        decodeUnchecked(control);
    }

    public static int withMaterialDetails(
            int control,
            boolean normalMap,
            boolean opticalMap,
            boolean tangentNegative) {
        int result = control
                | (normalMap ? CONTROL_NORMAL_TEXTURE : 0)
                | (opticalMap ? CONTROL_OPTICAL_TEXTURE : 0)
                | (normalMap && tangentNegative ? CONTROL_TANGENT_NEGATIVE : 0);
        requireValidControl(result);
        return result;
    }

    public static int packRasterCompositeControl(
            int control, int packedOverlayTint) {
        if ((control & CONTROL_RASTER_COMPOSITE) == 0) {
            throw new IllegalArgumentException(
                    "Raster composite payload requires its primitive flag");
        }
        requireValidControl(control);
        return physicalControl(control) | (packedOverlayTint & 0x00ff_ffff) << 3;
    }

    private static int physicalControl(int control) {
        return (control >>> 8 & 7) | (control & CONTROL_BUILTIN_MASK) << 16;
    }

    private static void decodeUnchecked(int control) {
        int scattering = control >>> CONTROL_SCATTERING_SHIFT & 3;
        boolean cutout = (control & CONTROL_ALPHA_CUTOUT) != 0;
        boolean water = (control & CONTROL_WATER_MEDIUM) != 0;
        boolean foliage = scattering == ScatteringFamily.FOLIAGE_THIN.encoded();
        boolean dielectricSolid = scattering == ScatteringFamily.DIELECTRIC_SOLID.encoded();
        boolean rasterComposite = (control & CONTROL_RASTER_COMPOSITE) != 0;
        if (water && !dielectricSolid) {
            throw new IllegalArgumentException("Water must be a solid dielectric");
        }
        if (foliage && !cutout) {
            throw new IllegalArgumentException("Foliage must use alpha-cutout coverage");
        }
        if ((control & CONTROL_TANGENT_NEGATIVE) != 0
                && (control & CONTROL_NORMAL_TEXTURE) == 0) {
            throw new IllegalArgumentException(
                    "Negative tangent handedness requires a normal texture");
        }
        if (rasterComposite
                && (cutout
                        || scattering != ScatteringFamily.OPAQUE.encoded()
                        || (control & (CONTROL_ANIMATED | CONTROL_FRONT_FACE_ONLY)) != 0)) {
            throw new IllegalArgumentException(
                    "Raster composites must be static, opaque, and two-sided");
        }
        BuiltinMaterialClass.fromId(control >>> CONTROL_BUILTIN_SHIFT & 15);
    }

    /**
     * Packs the UV tangent into the low 32 bits and reports negative bitangent handedness in bit
     * 32 of the returned value.
     * The geometric normal remains a separate field so normal mapping cannot perturb traversal
     * offsets, medium entry/exit tests, or ray-cone incidence.
     */
    public static long packTriangleTangent(
            float edgeOneX,
            float edgeOneY,
            float edgeOneZ,
            float edgeTwoX,
            float edgeTwoY,
            float edgeTwoZ,
            float deltaU1,
            float deltaV1,
            float deltaU2,
            float deltaV2,
            int packedNormal) {
        float determinant = deltaU1 * deltaV2 - deltaU2 * deltaV1;
        float normalX = unpackOctahedralComponent(packedNormal, true);
        float normalY = unpackOctahedralComponent(packedNormal, false);
        float normalZ = 1.0F - Math.abs(normalX) - Math.abs(normalY);
        if (normalZ < 0.0F) {
            float oldX = normalX;
            normalX = (1.0F - Math.abs(normalY)) * Math.copySign(1.0F, oldX);
            normalY = (1.0F - Math.abs(oldX)) * Math.copySign(1.0F, normalY);
        }
        float inverseNormalLength = 1.0F / (float) Math.sqrt(Math.max(
                normalX * normalX + normalY * normalY + normalZ * normalZ, 1.0e-20F));
        normalX *= inverseNormalLength;
        normalY *= inverseNormalLength;
        normalZ *= inverseNormalLength;
        float tangentX;
        float tangentY;
        float tangentZ;
        float bitangentX;
        float bitangentY;
        float bitangentZ;
        if (Math.abs(determinant) > 1.0e-20F && Float.isFinite(determinant)) {
            float inverse = 1.0F / determinant;
            tangentX = (edgeOneX * deltaV2 - edgeTwoX * deltaV1) * inverse;
            tangentY = (edgeOneY * deltaV2 - edgeTwoY * deltaV1) * inverse;
            tangentZ = (edgeOneZ * deltaV2 - edgeTwoZ * deltaV1) * inverse;
            bitangentX = (edgeTwoX * deltaU1 - edgeOneX * deltaU2) * inverse;
            bitangentY = (edgeTwoY * deltaU1 - edgeOneY * deltaU2) * inverse;
            bitangentZ = (edgeTwoZ * deltaU1 - edgeOneZ * deltaU2) * inverse;
        } else {
            float axisX = Math.abs(normalX) < 0.9F ? 1.0F : 0.0F;
            float axisY = axisX == 0.0F ? 1.0F : 0.0F;
            tangentX = axisY * normalZ;
            tangentY = -axisX * normalZ;
            tangentZ = axisX * normalY - axisY * normalX;
            bitangentX = normalY * tangentZ - normalZ * tangentY;
            bitangentY = normalZ * tangentX - normalX * tangentZ;
            bitangentZ = normalX * tangentY - normalY * tangentX;
        }
        float normalProjection = tangentX * normalX + tangentY * normalY + tangentZ * normalZ;
        tangentX -= normalProjection * normalX;
        tangentY -= normalProjection * normalY;
        tangentZ -= normalProjection * normalZ;
        float lengthSquared = tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ;
        if (!(lengthSquared > 1.0e-20F) || !Float.isFinite(lengthSquared)) {
            tangentX = Math.abs(normalX) < 0.9F ? 1.0F : 0.0F;
            tangentY = tangentX == 0.0F ? 1.0F : 0.0F;
            tangentZ = 0.0F;
            normalProjection = tangentX * normalX + tangentY * normalY;
            tangentX -= normalProjection * normalX;
            tangentY -= normalProjection * normalY;
            tangentZ -= normalProjection * normalZ;
            lengthSquared = tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ;
        }
        float inverseLength = 1.0F / (float) Math.sqrt(Math.max(lengthSquared, 1.0e-20F));
        tangentX *= inverseLength;
        tangentY *= inverseLength;
        tangentZ *= inverseLength;
        float crossX = normalY * tangentZ - normalZ * tangentY;
        float crossY = normalZ * tangentX - normalX * tangentZ;
        float crossZ = normalX * tangentY - normalY * tangentX;
        boolean negative = crossX * bitangentX + crossY * bitangentY + crossZ * bitangentZ < 0.0F;
        return Integer.toUnsignedLong(packOctahedralNormal(tangentX, tangentY, tangentZ))
                | (negative ? 0x1_0000_0000L : 0L);
    }

    private static float unpackOctahedralComponent(int packed, boolean low) {
        short value = (short) (low ? packed : packed >>> 16);
        return Math.max(-1.0F, value / 32767.0F);
    }

    public static int packOctahedralNormal(float x, float y, float z) {
        float inverseLength = 1.0F / Math.max(1.0e-20F, Math.abs(x) + Math.abs(y) + Math.abs(z));
        x *= inverseLength;
        y *= inverseLength;
        z *= inverseLength;
        if (z < 0.0F) {
            float oldX = x;
            x = (1.0F - Math.abs(y)) * Math.copySign(1.0F, oldX);
            y = (1.0F - Math.abs(oldX)) * Math.copySign(1.0F, y);
        }
        int packedX = packSnorm16(x);
        int packedY = packSnorm16(y);
        return packedX & 0xffff | packedY << 16;
    }

    /**
     * Packs the true triangle normal, falling back to the baked cardinal direction only for a
     * degenerate primitive.
     *
     * <p>Minecraft's {@code BakedQuad.direction()} is restricted to the six block directions.
     * Treating it as a geometric normal snaps rotated models such as crossed grass and flowers to
     * an axis. Besides incorrect shading, that makes ray-cone incidence select excessively coarse
     * alpha mips and can turn covered cutout texels into light leaks.
     */
    public static int packTriangleNormal(
            float edgeOneX,
            float edgeOneY,
            float edgeOneZ,
            float edgeTwoX,
            float edgeTwoY,
            float edgeTwoZ,
            float fallbackX,
            float fallbackY,
            float fallbackZ) {
        float normalX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
        float normalY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
        float normalZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
        float lengthSquared = normalX * normalX + normalY * normalY + normalZ * normalZ;
        if (!(lengthSquared > 1.0e-20F) || !Float.isFinite(lengthSquared)) {
            normalX = fallbackX;
            normalY = fallbackY;
            normalZ = fallbackZ;
            lengthSquared = normalX * normalX + normalY * normalY + normalZ * normalZ;
        } else if (normalX * fallbackX + normalY * fallbackY + normalZ * fallbackZ < 0.0F) {
            // Vertex winding is normally authoritative, but resource-provided baked quads may
            // disagree. Preserve the model's outward hemisphere without snapping its direction.
            normalX = -normalX;
            normalY = -normalY;
            normalZ = -normalZ;
        }
        float inverseLength = 1.0F / (float) Math.sqrt(Math.max(lengthSquared, 1.0e-20F));
        return packOctahedralNormal(
                normalX * inverseLength,
                normalY * inverseLength,
                normalZ * inverseLength);
    }

    /**
     * Packs the largest normalized-atlas UV change per world-space unit as one float.
     *
     * <p>This is the largest singular value of the triangle's world-to-UV differential. The hit
     * shader combines it with the actual atlas extent and the ray-cone footprint, so arbitrary
     * baked-model scaling is handled without storing triangle positions in the shader record.
     */
    public static int packUvDensity(
            float edge1X,
            float edge1Y,
            float edge1Z,
            float edge2X,
            float edge2Y,
            float edge2Z,
            float deltaU1,
            float deltaV1,
            float deltaU2,
            float deltaV2) {
        float normalX = edge1Y * edge2Z - edge1Z * edge2Y;
        float normalY = edge1Z * edge2X - edge1X * edge2Z;
        float normalZ = edge1X * edge2Y - edge1Y * edge2X;
        float denominator = normalX * normalX + normalY * normalY + normalZ * normalZ;
        if (!(denominator > 1.0e-20F) || !Float.isFinite(denominator)) {
            return Float.floatToRawIntBits(0.0F);
        }

        // cross(edge2, normal) and cross(normal, edge1) are the reciprocal tangent basis.
        float firstBasisX = edge2Y * normalZ - edge2Z * normalY;
        float firstBasisY = edge2Z * normalX - edge2X * normalZ;
        float firstBasisZ = edge2X * normalY - edge2Y * normalX;
        float secondBasisX = normalY * edge1Z - normalZ * edge1Y;
        float secondBasisY = normalZ * edge1X - normalX * edge1Z;
        float secondBasisZ = normalX * edge1Y - normalY * edge1X;
        float inverseDenominator = 1.0F / denominator;
        float gradientUx = (deltaU1 * firstBasisX + deltaU2 * secondBasisX) * inverseDenominator;
        float gradientUy = (deltaU1 * firstBasisY + deltaU2 * secondBasisY) * inverseDenominator;
        float gradientUz = (deltaU1 * firstBasisZ + deltaU2 * secondBasisZ) * inverseDenominator;
        float gradientVx = (deltaV1 * firstBasisX + deltaV2 * secondBasisX) * inverseDenominator;
        float gradientVy = (deltaV1 * firstBasisY + deltaV2 * secondBasisY) * inverseDenominator;
        float gradientVz = (deltaV1 * firstBasisZ + deltaV2 * secondBasisZ) * inverseDenominator;

        float uu = gradientUx * gradientUx + gradientUy * gradientUy + gradientUz * gradientUz;
        float vv = gradientVx * gradientVx + gradientVy * gradientVy + gradientVz * gradientVz;
        float uv = gradientUx * gradientVx + gradientUy * gradientVy + gradientUz * gradientVz;
        float discriminant = (uu - vv) * (uu - vv) + 4.0F * uv * uv;
        float largestEigenvalue = 0.5F * (uu + vv + (float) Math.sqrt(Math.max(discriminant, 0.0F)));
        float density = (float) Math.sqrt(Math.max(largestEigenvalue, 0.0F));
        return Float.floatToRawIntBits(Float.isFinite(density) ? density : 0.0F);
    }

    private static int packSnorm16(float value) {
        float clamped = Math.max(-1.0F, Math.min(1.0F, value));
        return Math.round(clamped * 32767.0F) & 0xffff;
    }
}
