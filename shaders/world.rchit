#version 460
#extension GL_GOOGLE_include_directive : require
#include "hit_common.glsl"

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

uint primeTriangleDebugHash(uint value) {
    value ^= value >> 16u;
    value *= 0x21f0aaadu;
    value ^= value >> 15u;
    value *= 0xf35a2d97u;
    return value ^ (value >> 15u);
}

vec3 primeTriangleDebugColor(PrimitiveRecord primitive) {
    // TLAS instance indices are allocation-order state and change whenever streamed clusters
    // rebuild the table. Primitive contents and their deterministic BLAS order remain stable.
    uint seed = primeTriangleDebugHash(primitive.uv0 ^ primitive.uv1 ^ primitive.uv2);
    seed = primeTriangleDebugHash(seed ^ primitive.tint);
    seed = primeTriangleDebugHash(seed ^ primitive.normal);
    seed = primeTriangleDebugHash(seed ^ primitive.flagsEmitter);
    seed = primeTriangleDebugHash(seed ^ primitive.uvDensity ^ primitive.tangent);
    seed = primeTriangleDebugHash(seed ^ gl_GeometryIndexEXT);
    seed = primeTriangleDebugHash(seed ^ gl_PrimitiveID);
    float hue = float(seed & 0xffffu) * (1.0 / 65536.0);
    vec3 rgb = clamp(
            abs(fract(hue + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0,
            0.0,
            1.0);
    return 0.9 * mix(vec3(1.0), rgb, 0.75);
}

void main() {
    SectionRecord section = primeSection();
    PrimitiveRecord primitive = primePrimitive(section);
    PrimeSurfaceRelation relation = primeSurfaceRelation(section);
    primePayload.hitKind = 1u;
    vec3 normal = primeUnpackOctahedralNormal(primitive.normal);
    bool bakedMaterial = primeUsesBakedMaterial(primitive);
    float textureLodValue =
            bakedMaterial ? 0.0 : primeRayConeTextureLod(primitive, normal);
    primitive = primeResolveSurfacePrimitive(
            section, primitive, relation, textureLodValue);
    bakedMaterial = primeUsesBakedMaterial(primitive);
    textureLodValue =
            bakedMaterial ? 0.0 : primeRayConeTextureLod(primitive, normal);
    vec2 materialUv = bakedMaterial
            ? vec2(0.0)
            : primeInterpolateUv(section, primitive);
    MaterialEvaluation material = primeEvaluateMaterial(
            primitive, materialUv, textureLodValue, section.instanceTint);
    primePayload.t = gl_HitTEXT;
    // Keep the authored outward normal. Opaque shading orients it at the integrator boundary,
    // while transparent reflection and the water stack require its authored outward sign.
    primePayload.geometricNormal = normal;
    primePayload.baseColor = primeVisualizesTriangles()
            ? mix(material.baseColor, primeTriangleDebugColor(primitive), 0.5)
            : material.baseColor;
    primePayload.traceKind = material.flags;
    primePayload.sectionIndex = gl_InstanceCustomIndexEXT;
    uint encodedEmitter = primeUsesRasterComposite(primitive)
            ? 0u
            : primitive.flagsEmitter >> 3u;
    if ((primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u
            && section.lightAddress != uint64_t(0)) {
        vec3 currentPosition = gl_WorldRayOriginEXT
                + gl_HitTEXT * gl_WorldRayDirectionEXT;
        vec3 motion = primePreviousDynamicPosition(section) - currentPosition;
        primePayload.emitterIndex = packHalf2x16(motion.xy);
        primePayload.hitKind |= PRIME_SURFACE_MOTION_FLAG
                | (packHalf2x16(vec2(0.0, motion.z)) & 0xffff0000u);
    } else {
        primePayload.emitterIndex = encodedEmitter == 0u
                ? 0xffffffffu
                : encodedEmitter - 1u;
    }
    primePayload.textureLod = floatBitsToUint(textureLodValue);
    primePayload.opacity = floatBitsToUint(material.opacity);
    primePayload.shadingNormal = primePackOctahedralNormal(material.shadingNormal);
    primePayload.labPbrNormal = material.labPbrNormal;
    primePayload.labPbrSpecular = material.labPbrSpecular;
    primePayload.adjacentBaseColor = vec3(0.0);
    primePayload.adjacentSpecularControl = 0u;
    if ((relation.control & PRIME_SURFACE_RELATION_KIND_MASK)
            == PRIME_SURFACE_RELATION_BOUNDARY) {
        bool water = (relation.control & PRIME_SURFACE_RELATION_WATER) != 0u;
        float referenceOpacity;
        primePayload.adjacentBaseColor = primeAtlasBaseColor(
                relation.tint,
                primeUnpackUv(relation.referenceUv),
                0.0,
                referenceOpacity);
        uint packedSpecular = (relation.control
                & PRIME_SURFACE_RELATION_LABPBR_SPECULAR) != 0u
                ? packUnorm4x8(textureLod(
                        primeLabPbrSpecularAtlas,
                        primeUnpackUv(relation.referenceUv),
                        0.0))
                : packUnorm4x8(vec4(0.0, 4.0 / 255.0, 0.0, 1.0));
        bool adjacentHasSpecular = (relation.control
                & PRIME_SURFACE_RELATION_LABPBR_SPECULAR) != 0u;
        uint control = PRIME_ADJACENT_MEDIUM_VALID
                | (water ? PRIME_ADJACENT_MEDIUM_WATER : 0u)
                | (!water && !primeGlassReferenceIsStained(referenceOpacity)
                        ? PRIME_ADJACENT_MEDIUM_COLORLESS
                        : 0u)
                | (adjacentHasSpecular
                        ? PRIME_ADJACENT_MEDIUM_LABPBR_SPECULAR
                        : 0u);
        uint adjacentFlags = PRIME_MATERIAL_FLAG_TRANSMISSIVE
                | (adjacentHasSpecular ? PRIME_MATERIAL_FLAG_LABPBR_SPECULAR : 0u);
        PrimeTranslatedLabPbrMaterial currentMaterial = primeDecodeAndTranslateLabPbr(
                material.labPbrNormal, material.labPbrSpecular, material.flags);
        PrimeTranslatedLabPbrMaterial adjacentMaterial = primeDecodeAndTranslateLabPbr(
                packUnorm4x8(vec4(0.5, 0.5, 1.0, 1.0)),
                packedSpecular,
                adjacentFlags);
        bool airGap = primeAirGapCompatible(
                primeUsesAirGap(),
                primeUsesSeamlessGlass(),
                material.flags,
                currentMaterial.dielectricF0,
                water,
                adjacentMaterial.dielectricF0);
        control |= airGap ? PRIME_ADJACENT_MEDIUM_AIR_GAP : 0u;
        primePayload.adjacentSpecularControl =
                (packedSpecular & 0x00ffffffu) | control;
    }
}
