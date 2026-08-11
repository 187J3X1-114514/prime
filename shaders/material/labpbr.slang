#ifndef PRIME_LABPBR_SLANG
#define PRIME_LABPBR_SLANG

#include "material/default_material.slang"

struct PrimeLabPbrSample
{
    float3 tangentNormal;
    float ambientOcclusion;
    float height;
    float perceptualRoughness;
    float dielectricF0;
    uint metalId;
    float porosity;
    float subsurface;
    float emission;
};

static const uint PRIME_RECIPE_NORMAL_TEXTURE = 1 << 5;
static const uint PRIME_RECIPE_OPTICAL_TEXTURE = 1 << 6;

bool primeHasLabPbrNormal(uint flags) { return (flags & PRIME_RECIPE_NORMAL_TEXTURE) != 0; }
bool primeHasLabPbrSpecular(uint flags) { return (flags & PRIME_RECIPE_OPTICAL_TEXTURE) != 0; }

struct PrimeCanonicalOptics
{
    float roughness;
    uint opticalControl;
};

PrimeCanonicalOptics primeAdaptLabPbrSpecular(float4 sourceSample)
{
    uint4 bytes = uint4(round(clamp(sourceSample, 0.0, 1.0) * 255.0));
    uint fresnelCode = bytes.y < 230
            ? bytes.y + 1
            : bytes.y <= 237
                    ? bytes.y + 1
                    : bytes.y == 255 ? PRIME_FRESNEL_CUSTOM_CONDUCTOR : 0;
    uint subsurfaceCode = bytes.z >= 66 ? bytes.z - 65 : 0;
    uint porosityCode = bytes.z <= 64 ? bytes.z : 0;
    PrimeCanonicalOptics result;
    result.roughness = clamp(1.0 - float(bytes.x) / 255.0, 0.0, 1.0);
    result.opticalControl = fresnelCode
            | subsurfaceCode << PRIME_OPTICAL_SUBSURFACE_SHIFT
            | porosityCode << PRIME_OPTICAL_POROSITY_SHIFT;
    return result;
}

float primeDecodeLabPbrEmission(uint encoded)
{
    return encoded < 255 ? float(encoded) / 254.0 : 0.0;
}

float primeDecodeLabPbrEmission(float encodedUnorm)
{
    return primeDecodeLabPbrEmission(
            uint(round(clamp(encodedUnorm, 0.0, 1.0) * 255.0)));
}

PrimeLabPbrSample primeDecodeLabPbr(uint packedNormal, uint packedSpecular, uint flags)
{
    uint4 normalBytes = uint4(round(unpackUnorm4x8ToFloat(packedNormal) * 255.0));
    uint4 specularBytes = uint4(round(unpackUnorm4x8ToFloat(packedSpecular) * 255.0));
    PrimeLabPbrSample result;
    float2 normalXY = float2(normalBytes.xy) * (2.0 / 255.0) - float2(1.0);
    float normalZ = sqrt(max(1.0 - dot(normalXY, normalXY), 0.0));
    result.tangentNormal = normalize(float3(normalXY, normalZ));
    result.ambientOcclusion = float(normalBytes.z) / 255.0;
    result.height = float(normalBytes.w) / 255.0;

    float smoothness = float(specularBytes.x) / 255.0;
    result.perceptualRoughness = clamp(1.0 - smoothness, 0.0, 1.0);
    result.metalId = specularBytes.y >= 230 ? specularBytes.y : 0;
    result.dielectricF0 = specularBytes.y < 230
            ? float(specularBytes.y) / 255.0
            : 0.0;
    result.porosity = specularBytes.z <= 64
            ? float(specularBytes.z) / 64.0
            : 0.0;
    result.subsurface = specularBytes.z >= 65
            ? float(specularBytes.z - 65) / 190.0
            : 0.0;
    result.emission = primeDecodeLabPbrEmission(specularBytes.w);

    if (!primeHasLabPbrNormal(flags)) {
        result.tangentNormal = float3(0.0, 0.0, 1.0);
        result.ambientOcclusion = 1.0;
        result.height = 1.0;
    }
    if (!primeHasLabPbrSpecular(flags)) {
        result.perceptualRoughness = PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
        result.dielectricF0 = PRIME_DEFAULT_DIELECTRIC_F0;
        result.metalId = 0;
        result.porosity = 0.0;
        result.subsurface = 0.0;
        result.emission = 0.0;
    }
    return result;
}

#endif
