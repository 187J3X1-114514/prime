#ifndef PRIME_HIT_COMMON_GLSL
#define PRIME_HIT_COMMON_GLSL

#extension GL_GOOGLE_include_directive : require
#include "common.glsl"
#include "material.glsl"

hitAttributeEXT vec2 primeBarycentrics;

layout(buffer_reference, buffer_reference_align = 4) readonly buffer PrimeMotionPositionBuffer {
    float values[];
};

SectionRecord primeSection() {
    SectionTable sections = SectionTable(primePush.sectionTableAddress);
    return sections.sections[gl_InstanceCustomIndexEXT];
}

PrimitiveRecord primePrimitive(SectionRecord section) {
    // BLAS geometries and the primitive buffer share one semantic order:
    // opaque, alpha-tested cutout, then transmissive. Macro rectangles occupy adjacent triangle
    // pairs at each geometry tail, where both triangles share one projected-UV primitive record.
    uint base = gl_GeometryIndexEXT == 0
            ? 0u
            : (gl_GeometryIndexEXT == 1 ? section.cutoutBase : section.transmissiveBase);
    uint macroBase = gl_GeometryIndexEXT == 0
            ? section.opaqueMacroTriangleBase
            : (gl_GeometryIndexEXT == 1
                    ? section.cutoutMacroTriangleBase
                    : section.transmissiveMacroTriangleBase);
    uint primitiveIndex = gl_PrimitiveID < macroBase
            ? gl_PrimitiveID
            : macroBase + ((gl_PrimitiveID - macroBase) >> 1u);
    PrimitiveBuffer primitives = PrimitiveBuffer(section.primitiveAddress);
    PrimitiveRecord primitive = primitives.records[base + primitiveIndex];
    // Baked material primitives may mix an untinted base with a tinted overlay in one BLAS.
    // Their instance tint is applied after per-primitive color selection in material.glsl.
    if ((section.instanceTint & 0x80000000u) != 0u
            && !primeUsesBakedMaterial(primitive)) {
        primitive.tint = (primitive.tint & 0xff000000u)
                | (section.instanceTint & 0x00ffffffu);
    }
    return primitive;
}

PrimitiveRecord primePrimitive() {
    return primePrimitive(primeSection());
}

vec3 primePreviousDynamicPosition(SectionRecord section) {
    PrimeMotionPositionBuffer positions = PrimeMotionPositionBuffer(section.lightAddress);
    uint vertexBase = gl_PrimitiveID * 9u;
    vec3 barycentric = vec3(
            1.0 - primeBarycentrics.x - primeBarycentrics.y,
            primeBarycentrics.x,
            primeBarycentrics.y);
    vec3 first = vec3(
            positions.values[vertexBase],
            positions.values[vertexBase + 1u],
            positions.values[vertexBase + 2u]);
    vec3 second = vec3(
            positions.values[vertexBase + 3u],
            positions.values[vertexBase + 4u],
            positions.values[vertexBase + 5u]);
    vec3 third = vec3(
            positions.values[vertexBase + 6u],
            positions.values[vertexBase + 7u],
            positions.values[vertexBase + 8u]);
    return first * barycentric.x + second * barycentric.y + third * barycentric.z
            + section.translation;
}

bool primeUsesRepeatedUv(PrimitiveRecord primitive) {
    return primeUsesRasterComposite(primitive)
            || uintBitsToFloat(primitive.uvDensity) < 0.0;
}

bool primeUsesConstantFloatUv(PrimitiveRecord primitive) {
    // Negative zero is distinct at the ABI level while remaining outside the negative-density
    // macro-face encoding. Voxel texel centers need float32 precision on large stitched atlases.
    return primitive.uvDensity == PRIME_CONSTANT_UV_DENSITY;
}

vec2 primeInterpolateUv(SectionRecord section, PrimitiveRecord primitive) {
    if (primeUsesConstantFloatUv(primitive)) {
        return vec2(uintBitsToFloat(primitive.uv0), uintBitsToFloat(primitive.uv1));
    }
    if (primeUsesRepeatedUv(primitive)) {
        vec3 hitPosition = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT;
        vec3 localPosition = hitPosition - section.translation;
        vec3 normal = abs(primeUnpackOctahedralNormal(primitive.normal));
        vec2 projectedPosition = normal.x > normal.y && normal.x > normal.z
                ? localPosition.yz
                : (normal.y > normal.z ? localPosition.xz : localPosition.xy);
        vec2 repeatedPosition = fract(projectedPosition);
        vec2 uv0 = primeUnpackHalf2(primitive.uv0);
        vec2 uv1 = primeUnpackHalf2(primitive.uv1);
        vec2 uv2 = primeUnpackHalf2(primitive.uv2);
        return uv0
                + repeatedPosition.x * (uv1 - uv0)
                + repeatedPosition.y * (uv2 - uv0);
    }
    vec3 barycentric = vec3(1.0 - primeBarycentrics.x - primeBarycentrics.y,
            primeBarycentrics.x, primeBarycentrics.y);
    return primeUnpackHalf2(primitive.uv0) * barycentric.x
            + primeUnpackHalf2(primitive.uv1) * barycentric.y
            + primeUnpackHalf2(primitive.uv2) * barycentric.z;
}

float primeRayConeTextureLod(PrimitiveRecord primitive, vec3 geometricNormal) {
    vec2 rayCone = unpackHalf2x16(primePush.rayCone);
    float normalizedUvDensity;
    if (primeUsesRasterComposite(primitive)) {
        vec2 first = primeUnpackHalf2(primitive.uv1)
                - primeUnpackHalf2(primitive.uv0);
        vec2 second = primeUnpackHalf2(primitive.uv2)
                - primeUnpackHalf2(primitive.uv0);
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
        normalizedUvDensity = abs(uintBitsToFloat(primitive.uvDensity));
    }
    uint textureIndex = min(
            primePrimitiveTextureIndex(primitive), PRIME_SCENE_TEXTURE_COUNT - 1u);
    int mipLevels = textureQueryLevels(
            primeSceneTextures[nonuniformEXT(textureIndex)]);
    if (!(rayCone.x > 0.0) || !(normalizedUvDensity > 0.0) || mipLevels <= 1) {
        return 0.0;
    }

    // The CPU stores the largest world-to-normalized-UV singular value. Multiplying by the
    // actual atlas extent turns it into texels per world unit without baking resource-pack size
    // into section meshes. Minecraft block atlases are square; max() remains conservative if a
    // backend exposes a non-square view.
    ivec2 atlasExtent = textureSize(
            primeSceneTextures[nonuniformEXT(textureIndex)], 0);
    float texelsPerWorldUnit = normalizedUvDensity
            * float(max(atlasExtent.x, atlasExtent.y));
    vec3 hitPosition = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT;
    float distanceFromCamera = length(hitPosition - primePush.cameraPosition);
    float incidence = max(abs(dot(
            normalize(geometricNormal), normalize(gl_WorldRayDirectionEXT))), 0.05);
    float texelFootprint = distanceFromCamera * rayCone.x
            * texelsPerWorldUnit / incidence;

    // rayCone.y is FSR's quality-mode mip bias. It must be applied after deriving the native
    // screen-space footprint; applying a fixed mip directly would make distant shimmer look like
    // detail and cause FSR's luminance-instability stage to erase it over time.
    float lod = log2(max(texelFootprint, 1.0e-8)) + rayCone.y;
    return clamp(lod, 0.0, float(mipLevels - 1));
}

#endif
