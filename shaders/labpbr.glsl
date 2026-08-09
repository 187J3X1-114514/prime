#ifndef PRIME_LABPBR_GLSL
#define PRIME_LABPBR_GLSL

#include "default_material.glsl"

// LabPBR 1.3 is an integer UNORM transport format. Keep every decoded channel here even when the
// current renderer does not consume it: tangent normals, AO, height and porosity must not be
// reinterpreted when their later shading/displacement/weather systems are connected.
struct PrimeLabPbrSample {
    vec3 tangentNormal;
    float ambientOcclusion;
    float height;
    float perceptualRoughness;
    float dielectricF0;
    uint metalId;
    float porosity;
    float subsurface;
    float emission;
};

const uint PRIME_RECIPE_NORMAL_TEXTURE = 1u << 5u;
const uint PRIME_RECIPE_OPTICAL_TEXTURE = 1u << 6u;

bool primeHasLabPbrNormal(uint flags) {
    return (flags & PRIME_RECIPE_NORMAL_TEXTURE) != 0u;
}

bool primeHasLabPbrSpecular(uint flags) {
    return (flags & PRIME_RECIPE_OPTICAL_TEXTURE) != 0u;
}

struct PrimeCanonicalOptics {
    float roughness;
    uint opticalControl;
};

PrimeCanonicalOptics primeAdaptLabPbrSpecular(vec4 sourceSample) {
    uvec4 bytes = uvec4(round(clamp(sourceSample, 0.0, 1.0) * 255.0));
    uint fresnelCode = bytes.y < 230u
            ? bytes.y + 1u
            : bytes.y <= 237u
                    ? bytes.y + 1u
                    : bytes.y == 255u ? PRIME_FRESNEL_CUSTOM_CONDUCTOR : 0u;
    uint subsurfaceCode = bytes.z >= 66u ? bytes.z - 65u : 0u;
    uint porosityCode = bytes.z <= 64u ? bytes.z : 0u;
    PrimeCanonicalOptics result;
    result.roughness = clamp(1.0 - float(bytes.x) / 255.0, 0.0, 1.0);
    result.opticalControl = fresnelCode
            | subsurfaceCode << PRIME_OPTICAL_SUBSURFACE_SHIFT
            | porosityCode << PRIME_OPTICAL_POROSITY_SHIFT;
    return result;
}

float primeDecodeLabPbrEmission(uint encoded) {
    return encoded < 255u ? float(encoded) / 254.0 : 0.0;
}

float primeDecodeLabPbrEmission(float encodedUnorm) {
    return primeDecodeLabPbrEmission(uint(round(clamp(encodedUnorm, 0.0, 1.0) * 255.0)));
}

PrimeLabPbrSample primeDecodeLabPbr(uint packedNormal, uint packedSpecular, uint flags) {
    uvec4 normalBytes = uvec4(round(unpackUnorm4x8(packedNormal) * 255.0));
    uvec4 specularBytes = uvec4(round(unpackUnorm4x8(packedSpecular) * 255.0));
    PrimeLabPbrSample result;
    vec2 normalXY = vec2(normalBytes.xy) * (2.0 / 255.0) - vec2(1.0);
    float normalZ = sqrt(max(1.0 - dot(normalXY, normalXY), 0.0));
    result.tangentNormal = normalize(vec3(normalXY, normalZ));
    result.ambientOcclusion = float(normalBytes.z) / 255.0;
    result.height = float(normalBytes.w) / 255.0;

    float smoothness = float(specularBytes.x) / 255.0;
    // UNORM decoding is mathematically closed on [0, 1], but reciprocal-255 constant folding may
    // put the 255 endpoint one ulp above one on a GPU. Keep the public material domain exact.
    result.perceptualRoughness = clamp(1.0 - smoothness, 0.0, 1.0);
    result.metalId = specularBytes.y >= 230u ? specularBytes.y : 0u;
    result.dielectricF0 = specularBytes.y < 230u
            ? float(specularBytes.y) / 255.0
            : 0.0;
    result.porosity = specularBytes.z <= 64u
            ? float(specularBytes.z) / 64.0
            : 0.0;
    result.subsurface = specularBytes.z >= 65u
            ? float(specularBytes.z - 65u) / 190.0
            : 0.0;
    // 255 is the standard's "no authored emission" sentinel, not a 100% value.
    result.emission = primeDecodeLabPbrEmission(specularBytes.w);

    if (!primeHasLabPbrNormal(flags)) {
        result.tangentNormal = vec3(0.0, 0.0, 1.0);
        result.ambientOcclusion = 1.0;
        result.height = 1.0;
    }
    if (!primeHasLabPbrSpecular(flags)) {
        result.perceptualRoughness = PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
        result.dielectricF0 = PRIME_DEFAULT_DIELECTRIC_F0;
        result.metalId = 0u;
        result.porosity = 0.0;
        result.subsurface = 0.0;
        result.emission = 0.0;
    }
    return result;
}

#endif
