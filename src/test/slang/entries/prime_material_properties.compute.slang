#include "core/prime_test_abi.slang"
import "material/labpbr.slang";
import "material/default_material.slang";
import "material/material_translation.slang";

static const uint PRIME_MATERIAL_INPUT_WORDS = 3;
static const uint PRIME_MATERIAL_WITNESS_WORDS = 8;

void primeMaterialClassify(inout uint mask, float value) { mask |= primeTestClassify(value); }
void primeMaterialClassify(inout uint mask, float3 value) { mask |= primeTestClassify(value); }
bool primeMaterialUnit(float value) { return value >= 0.0 && value <= 1.0; }
bool primeMaterialUnit(float3 value)
{
    return all(value >= float3(0.0)) && all(value <= float3(1.0));
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_MATERIAL_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    float4 baseAndCosine = asfloat(primeTestInput[inputOffset + 1]);
    uint4 expected = primeTestInput[inputOffset + 2];
    uint kind = control.x;
    uint flags = control.y;
    uint packedNormal = control.z;
    uint packedSpecular = control.w;

    uint mask = 0;
    PrimeLabPbrSample decoded = primeDecodeLabPbr(packedNormal, packedSpecular, flags);
    PrimeCanonicalOptics canonical = primeAdaptLabPbrSpecular(
            unpackUnorm4x8ToFloat(packedSpecular));
    uint4 specularBytes = uint4(round(unpackUnorm4x8ToFloat(packedSpecular) * 255.0));
    uint expectedFresnelCode = specularBytes.y < 230
            ? specularBytes.y + 1
            : specularBytes.y <= 237
                    ? specularBytes.y + 1
                    : specularBytes.y == 255 ? PRIME_FRESNEL_CUSTOM_CONDUCTOR : 0;
    uint expectedSubsurfaceCode = specularBytes.z >= 66 ? specularBytes.z - 65 : 0;
    uint expectedPorosityCode = specularBytes.z <= 64 ? specularBytes.z : 0;
    if (canonical.roughness
                    != clamp(1.0 - float(specularBytes.x) / 255.0, 0.0, 1.0)
            || canonical.opticalControl != (expectedFresnelCode
                    | expectedSubsurfaceCode << PRIME_OPTICAL_SUBSURFACE_SHIFT
                    | expectedPorosityCode << PRIME_OPTICAL_POROSITY_SHIFT)) {
        mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    bool hasOpticalTexture = primeHasLabPbrSpecular(flags);
    float roughness = hasOpticalTexture
            ? canonical.roughness
            : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    uint opticalControl = hasOpticalTexture ? canonical.opticalControl : 0;
    PrimeOpticalMaterial translated = primeDecodeOpticalMaterial(
            roughness, opticalControl, PRIME_MATERIAL_FAMILY_OPAQUE);
    float normalLengthSquared = dot(decoded.tangentNormal, decoded.tangentNormal);
    primeMaterialClassify(mask, decoded.tangentNormal);
    primeMaterialClassify(mask, normalLengthSquared);
    primeMaterialClassify(mask, float3(
            decoded.ambientOcclusion, decoded.height, decoded.perceptualRoughness));
    primeMaterialClassify(mask, float3(
            decoded.dielectricF0, decoded.porosity, decoded.subsurface));
    primeMaterialClassify(mask, decoded.emission);
    if (!primeTestClose(normalLengthSquared, 1.0, 2.0e-6, 2.0e-6)) {
        mask |= PRIME_TEST_FAILURE_UNIT_LENGTH;
    }
    if (!primeMaterialUnit(decoded.ambientOcclusion)
            || !primeMaterialUnit(decoded.height)
            || !primeMaterialUnit(decoded.perceptualRoughness)
            || !primeMaterialUnit(decoded.dielectricF0)
            || !primeMaterialUnit(decoded.porosity)
            || !primeMaterialUnit(decoded.subsurface)
            || !primeMaterialUnit(decoded.emission)) {
        mask |= PRIME_TEST_FAILURE_RANGE;
    }
    if (!primeHasLabPbrNormal(flags)
            && (!all(decoded.tangentNormal == float3(0.0, 0.0, 1.0))
                    || decoded.ambientOcclusion != 1.0
                    || decoded.height != 1.0)) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }
    if (!primeHasLabPbrSpecular(flags)
            && (decoded.perceptualRoughness != PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS
                    || decoded.dielectricF0 != PRIME_DEFAULT_DIELECTRIC_F0
                    || decoded.metalId != 0
                    || decoded.porosity != 0.0
                    || decoded.subsurface != 0.0
                    || decoded.emission != 0.0)) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }

    primeMaterialClassify(mask, float3(
            translated.perceptualRoughness,
            translated.dielectricF0,
            translated.subsurfaceWeight));
    if (!primeMaterialUnit(translated.perceptualRoughness)
            || !primeMaterialUnit(translated.dielectricF0)
            || !primeMaterialUnit(translated.subsurfaceWeight)
            || (primeFresnelIsConductor(translated.fresnelCode)
                    && !primeIsSupportedConductorCode(translated.fresnelCode))
            || translated.thinWalled != 0) {
        mask |= PRIME_TEST_FAILURE_RANGE;
    }
    uint solidTransmission = PRIME_MATERIAL_FAMILY_DIELECTRIC
            | PRIME_MATERIAL_MEDIUM_STAINED_GLASS;
    uint fresnelCode = opticalControl & PRIME_OPTICAL_FRESNEL_MASK;
    uint otherFresnelCode = fresnelCode == 0 ? 1 : 0;
    if (!primeAirGapCompatible(
                    true, solidTransmission, fresnelCode, solidTransmission, fresnelCode)
            || primeAirGapCompatible(
                    false, solidTransmission, fresnelCode, solidTransmission, fresnelCode)
            || primeAirGapCompatible(
                    true, solidTransmission | PRIME_MATERIAL_THIN_WALLED,
                    fresnelCode, solidTransmission, fresnelCode)
            || primeAirGapCompatible(
                    true, PRIME_MATERIAL_FAMILY_DIELECTRIC | PRIME_MATERIAL_MEDIUM_WATER,
                    fresnelCode, solidTransmission, fresnelCode)
            || primeAirGapCompatible(
                    true, solidTransmission | PRIME_MATERIAL_DECORATIVE_INTERFACE,
                    fresnelCode, solidTransmission, fresnelCode)
            || primeAirGapCompatible(
                    true, solidTransmission, fresnelCode,
                    PRIME_MATERIAL_FAMILY_OPAQUE, fresnelCode)
            || primeAirGapCompatible(
                    true, solidTransmission, fresnelCode,
                    solidTransmission, otherFresnelCode)) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }

    PrimeConductorFresnel fresnel = primeConductorFresnel(baseAndCosine.xyz, expected.x);
    primeMaterialClassify(mask, fresnel.f0);
    primeMaterialClassify(mask, fresnel.f82Tint);
    if (!primeMaterialUnit(fresnel.f0) || !primeMaterialUnit(fresnel.f82Tint)) {
        mask |= PRIME_TEST_FAILURE_RANGE;
    }
    float3 eta;
    float3 k;
    bool standard = primeConductorOpticalConstants(expected.x, eta, k);
    if (standard != primeIsStandardConductorCode(expected.x)) {
        mask |= PRIME_TEST_FAILURE_EVENT;
    }
    if (standard) {
        float3 reflectance = primeConductorReflectance(baseAndCosine.w, eta, k);
        primeMaterialClassify(mask, reflectance);
        if (!primeMaterialUnit(reflectance)) mask |= PRIME_TEST_FAILURE_RANGE;
    }
    if (asuint(primeBuiltinRoughness(expected.y)) != expected.z
            || primeBuiltinFresnelCode(expected.y) != expected.w) {
        mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    if (kind > 1) mask |= PRIME_TEST_FAILURE_EVENT;

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(mask, kind, caseIndex, 0);
    if (outputWords >= PRIME_MATERIAL_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1] = control;
        primeTestOutput[outputOffset + 2] = asuint(baseAndCosine);
        primeTestOutput[outputOffset + 3] = expected;
        primeTestOutput[outputOffset + 4] = asuint(float4(
                decoded.tangentNormal, normalLengthSquared));
        primeTestOutput[outputOffset + 5] = asuint(float4(
                decoded.perceptualRoughness,
                decoded.dielectricF0,
                decoded.subsurface,
                decoded.emission));
        primeTestOutput[outputOffset + 6] = asuint(float4(
                translated.perceptualRoughness,
                translated.dielectricF0,
                translated.subsurfaceWeight,
                float(translated.fresnelCode)));
        primeTestOutput[outputOffset + 7] = asuint(float4(fresnel.f0, fresnel.f82Tint.x));
    }
}
