#language slang 2026
module "rt/hit_common.slang";

import glsl;
import "prime_abi.slang";
import "prime_abi_types.slang";
import "core/packed_geometry.slang";
import "material/default_material.slang";
import "material/medium_boundary.slang";
import "material/material.slang";


public struct PrimeSurfaceRelation {
    public uint control;
    public uint referenceUv;
    public uint tint;
    public PrimitiveRecord material;
};

public static const uint PRIME_SURFACE_RELATION_KIND_MASK = 0xfu;
public static const uint PRIME_SURFACE_RELATION_BOUNDARY = 1u;
static const uint PRIME_SURFACE_RELATION_OVERLAY = 2u;
static const uint PRIME_SURFACE_RELATION_BILATERAL = 3u;
public static const uint PRIME_SURFACE_RELATION_MICRO_GAP_ELIGIBLE = 1u << 4u;
static const uint PRIME_SURFACE_RELATION_POSITIVE_ONLY = 1u << 4u;

public SectionRecord primeSection() {
    return ((SectionRecord*)primePush.sectionTableAddress)[InstanceID()];
}

uint primeLocalPrimitiveIndex(SectionRecord section) {
    // BLAS geometries and the primitive buffer share one semantic order:
    // opaque, alpha-tested cutout, then transmissive. Macro rectangles occupy adjacent triangle
    // pairs at each geometry tail, where both triangles share one projected-UV primitive record.
    uint macroBase = GeometryIndex() == 0
            ? section.opaqueMacroTriangleBase
            : (GeometryIndex() == 1
                    ? section.cutoutMacroTriangleBase
                    : section.transmissiveMacroTriangleBase);
    return PrimitiveIndex() < macroBase
            ? PrimitiveIndex()
            : macroBase + ((PrimitiveIndex() - macroBase) >> 1u);
}

public PrimitiveRecord primePrimitive(SectionRecord section) {
    uint base = GeometryIndex() == 0
            ? 0u
            : (GeometryIndex() == 1 ? section.cutoutBase : section.transmissiveBase);
    uint primitiveIndex = primeLocalPrimitiveIndex(section);
    PrimitiveRecord primitive =
            ((PrimitiveRecord*)section.primitiveAddress)[base + primitiveIndex];
    // Baked material primitives may lerp an untinted base with a tinted overlay in one BLAS.
    // Their instance tint is applied after per-primitive color selection in material.slang.
    if ((section.instanceTint & 0x80000000u) != 0u
            && !primeUsesBakedMaterial(primitive)) {
        primitive.tint = (primitive.tint & 0xff000000u)
                | (section.instanceTint & 0x00ffffffu);
    }
    return primitive;
}

uint primeGlobalPrimitiveIndex(SectionRecord section) {
    uint base = GeometryIndex() == 0
            ? 0u
            : (GeometryIndex() == 1 ? section.cutoutBase : section.transmissiveBase);
    return base + primeLocalPrimitiveIndex(section);
}

public PrimeSurfaceRelation primeSurfaceRelation(SectionRecord section) {
    PrimeSurfaceRelation result;
    result.control = 0u;
    result.referenceUv = 0u;
    result.tint = 0u;
    result.material.uv0 = 0u;
    result.material.uv1 = 0u;
    result.material.uv2 = 0u;
    result.material.tint = 0u;
    result.material.normal = 0u;
    result.material.flagsEmitter = 0u;
    result.material.uvDensity = 0u;
    result.material.tangent = 0u;
    if (section.surfaceRelationAddress == uint64_t(0)) {
        return result;
    }
    uint* relations = (uint*)section.surfaceRelationAddress;
    uint offset = relations[primeGlobalPrimitiveIndex(section)];
    if (offset == 0u) {
        return result;
    }
    result.control = relations[offset];
    uint kind = result.control & PRIME_SURFACE_RELATION_KIND_MASK;
    if (kind == PRIME_SURFACE_RELATION_BOUNDARY) {
        result.referenceUv = relations[offset + 1u];
        result.tint = relations[offset + 2u];
    } else {
        result.material.uv0 = relations[offset + 1u];
        result.material.uv1 = relations[offset + 2u];
        result.material.uv2 = relations[offset + 3u];
        result.material.tint = relations[offset + 4u];
        result.material.normal = relations[offset + 5u];
        result.material.flagsEmitter = relations[offset + 6u];
        result.material.uvDensity = relations[offset + 7u];
        result.material.tangent = relations[offset + 8u];
    }
    return result;
}

PrimitiveRecord primePrimitiveWithMaterialRecipe(
        PrimitiveRecord primitive, uint recipe) {
    uint control = (primePrimitiveControl(primitive) & ~PRIME_RECIPE_MATERIAL_MASK)
            | (recipe & PRIME_RECIPE_MATERIAL_MASK);
    primitive.tint = (primitive.tint & 0x00ffffffu) | ((control & 0xffu) << 24u);
    primitive.flagsEmitter = (primitive.flagsEmitter & ~0x78000007u)
            | ((control >> 8u) & 7u)
            | ((control & PRIME_RECIPE_BUILTIN_MASK) << 16u);
    return primitive;
}

public float2 primeInterpolateUv(
        SectionRecord section, PrimitiveRecord primitive, float2 barycentrics);

public PrimitiveRecord primeResolveSurfacePrimitive(
        SectionRecord section,
        PrimitiveRecord primary,
        PrimeSurfaceRelation relation,
        float textureLodValue,
        float2 barycentrics) {
    uint kind = relation.control & PRIME_SURFACE_RELATION_KIND_MASK;
    bool backFacing = HitKind() == HIT_KIND_TRIANGLE_BACK_FACE;
    if (kind == PRIME_SURFACE_RELATION_BILATERAL && backFacing) {
        return relation.material;
    }
    if (kind != PRIME_SURFACE_RELATION_OVERLAY) {
        return primary;
    }
    bool positiveOnly =
            (relation.control & PRIME_SURFACE_RELATION_POSITIVE_ONLY) != 0u;
    bool overlayCovered = !(positiveOnly && backFacing)
            && primeEvaluateOpacity(
                    primary,
                    primeInterpolateUv(section, primary, barycentrics),
                    textureLodValue) >= PRIME_CUTOUT_ALPHA_THRESHOLD;
    return overlayCovered
            ? primePrimitiveWithMaterialRecipe(primary, relation.control >> 8u)
            : relation.material;
}

public PrimitiveRecord primePrimitive() {
    return primePrimitive(primeSection());
}

public float3 primePreviousDynamicPosition(SectionRecord section, float2 barycentrics) {
    float* positions = (float*)section.lightAddress;
    uint vertexBase = PrimitiveIndex() * 9u;
    float3 barycentric = float3(
            1.0 - barycentrics.x - barycentrics.y,
            barycentrics.x,
            barycentrics.y);
    float3 first = float3(
            positions[vertexBase],
            positions[vertexBase + 1u],
            positions[vertexBase + 2u]);
    float3 second = float3(
            positions[vertexBase + 3u],
            positions[vertexBase + 4u],
            positions[vertexBase + 5u]);
    float3 third = float3(
            positions[vertexBase + 6u],
            positions[vertexBase + 7u],
            positions[vertexBase + 8u]);
    return first * barycentric.x + second * barycentric.y + third * barycentric.z
            + section.translation;
}

bool primeUsesRepeatedUv(PrimitiveRecord primitive) {
    return primeUsesRasterComposite(primitive)
            || asfloat(primitive.uvDensity) < 0.0;
}

bool primeUsesConstantFloatUv(PrimitiveRecord primitive) {
    // Negative zero is distinct at the ABI level while remaining outside the negative-density
    // macro-face encoding. Voxel texel centers need float32 precision on large stitched atlases.
    return primitive.uvDensity == PRIME_CONSTANT_UV_DENSITY;
}

public float2 primeInterpolateUv(
        SectionRecord section, PrimitiveRecord primitive, float2 barycentrics) {
    if (primeUsesConstantFloatUv(primitive)) {
        return float2(asfloat(primitive.uv0), asfloat(primitive.uv1));
    }
    if (primeUsesRepeatedUv(primitive)) {
        float3 hitPosition = WorldRayOrigin() + RayTCurrent() * WorldRayDirection();
        float3 localPosition = hitPosition - section.translation;
        float3 normal = abs(primeUnpackOctahedralNormal(primitive.normal));
        float2 projectedPosition = normal.x > normal.y && normal.x > normal.z
                ? localPosition.yz
                : (normal.y > normal.z ? localPosition.xz : localPosition.xy);
        float2 repeatedPosition = frac(projectedPosition);
        float2 uv0 = primeUnpackUv(primitive.uv0);
        float2 uv1 = primeUnpackUv(primitive.uv1);
        float2 uv2 = primeUnpackUv(primitive.uv2);
        return uv0
                + repeatedPosition.x * (uv1 - uv0)
                + repeatedPosition.y * (uv2 - uv0);
    }
    float3 barycentric = float3(
            1.0 - barycentrics.x - barycentrics.y,
            barycentrics.x,
            barycentrics.y);
    return primeUnpackUv(primitive.uv0) * barycentric.x
            + primeUnpackUv(primitive.uv1) * barycentric.y
            + primeUnpackUv(primitive.uv2) * barycentric.z;
}

public float primeRayConeTextureLod(PrimitiveRecord primitive, float3 geometricNormal) {
    float2 rayCone = unpackHalf2x16ToFloat(primePush.rayCone);
    float normalizedUvDensity;
    if (primeUsesRasterComposite(primitive)) {
        float2 first = primeUnpackUv(primitive.uv1)
                - primeUnpackUv(primitive.uv0);
        float2 second = primeUnpackUv(primitive.uv2)
                - primeUnpackUv(primitive.uv0);
        float trace = dot(first, first) + dot(second, second);
        float determinant =
                first.x * second.y - first.y * second.x;
        normalizedUvDensity = sqrt(max(
                0.5 * (trace + sqrt(max(
                        trace * trace
                                - 4.0 * determinant * determinant,
                        0.0))),
                0.0));
    } else {
        normalizedUvDensity = abs(asfloat(primitive.uvDensity));
    }
    uint textureIndex = min(
            primePrimitiveTextureIndex(primitive), PRIME_SCENE_TEXTURE_COUNT - 1u);
    int mipLevels = primeSceneTextureMipLevels(textureIndex);
    if (!(rayCone.x > 0.0) || !(normalizedUvDensity > 0.0) || mipLevels <= 1) {
        return 0.0;
    }

    // The CPU stores the largest world-to-normalized-UV singular value. Multiplying by the
    // actual atlas extent turns it into texels per world unit without baking resource-pack size
    // into section meshes. Minecraft block atlases are square; max() remains conservative if a
    // backend exposes a non-square view.
    int2 atlasExtent = primeSceneTextureExtent(textureIndex);
    float texelsPerWorldUnit = normalizedUvDensity
            * float(max(atlasExtent.x, atlasExtent.y));
    float3 hitPosition = WorldRayOrigin() + RayTCurrent() * WorldRayDirection();
    float distanceFromCamera = length(hitPosition - primePush.cameraPosition);
    float incidence = max(abs(dot(
            normalize(geometricNormal), normalize(WorldRayDirection()))), 0.05);
    float texelFootprint = distanceFromCamera * rayCone.x
            * texelsPerWorldUnit / incidence;

    // rayCone.y is FSR's quality-mode mip bias. It must be applied after deriving the native
    // screen-space footprint; applying a fixed mip directly would make distant shimmer look like
    // detail and cause FSR's luminance-instability stage to erase it over time.
    float lod = log2(max(texelFootprint, 1.0e-8)) + rayCone.y;
    return clamp(lod, 0.0, float(mipLevels - 1));
}
