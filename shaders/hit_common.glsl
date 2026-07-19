#ifndef PRIME_HIT_COMMON_GLSL
#define PRIME_HIT_COMMON_GLSL

#extension GL_GOOGLE_include_directive : require
#include "common.glsl"
#include "material.glsl"

hitAttributeEXT vec2 primeBarycentrics;

SectionRecord primeSection() {
    SectionTable sections = SectionTable(primePush.sectionTableAddress);
    return sections.sections[gl_InstanceCustomIndexEXT];
}

PrimitiveRecord primePrimitive(SectionRecord section) {
    // BLAS geometries and the primitive buffer share one semantic order:
    // opaque, alpha-tested cutout, then physically transmissive. Opaque starts at zero, so the
    // two saved bases fit the existing 64-byte Section ABI without an otherwise-useless zero.
    uint base = gl_GeometryIndexEXT == 0
            ? 0u
            : (gl_GeometryIndexEXT == 1 ? section.cutoutBase : section.transmissiveBase);
    PrimitiveBuffer primitives = PrimitiveBuffer(section.primitiveAddress);
    return primitives.records[base + gl_PrimitiveID];
}

PrimitiveRecord primePrimitive() {
    return primePrimitive(primeSection());
}

vec2 primeInterpolateUv(PrimitiveRecord primitive) {
    vec3 barycentric = vec3(1.0 - primeBarycentrics.x - primeBarycentrics.y,
            primeBarycentrics.x, primeBarycentrics.y);
    return primeUnpackHalf2(primitive.uv0) * barycentric.x
            + primeUnpackHalf2(primitive.uv1) * barycentric.y
            + primeUnpackHalf2(primitive.uv2) * barycentric.z;
}

float primeRayConeTextureLod(PrimitiveRecord primitive, vec3 geometricNormal) {
    vec2 rayCone = unpackHalf2x16(primePush.rayCone);
    float normalizedUvDensity = max(uintBitsToFloat(primitive.uvDensity), 0.0);
    int mipLevels = textureQueryLevels(primeBlockAtlas);
    if (!(rayCone.x > 0.0) || !(normalizedUvDensity > 0.0) || mipLevels <= 1) {
        return 0.0;
    }

    // The CPU stores the largest world-to-normalized-UV singular value. Multiplying by the
    // actual atlas extent turns it into texels per world unit without baking resource-pack size
    // into section meshes. Minecraft block atlases are square; max() remains conservative if a
    // backend exposes a non-square view.
    ivec2 atlasExtent = textureSize(primeBlockAtlas, 0);
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
