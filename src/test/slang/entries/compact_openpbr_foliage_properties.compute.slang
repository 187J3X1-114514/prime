#include "core/prime_test_abi.slang"
import "bsdf/adapter/prime_bsdf_specializations.slang";
import "bsdf/adapter/transmission_ggx_energy.slang";
import "bsdf/compact/openpbr_foliage.slang";
import "bsdf/core/robocute_common.slang";
import "bsdf/core/robocute_openpbr.slang";

static const uint PRIME_COMPACT_FOLIAGE_INPUT_WORDS = 4u;
static const uint PRIME_COMPACT_FOLIAGE_WITNESS_WORDS = 12u;
static const float PRIME_TEST_FOLIAGE_TRANSMISSION_WEIGHT = 0.15;
static const float3 PRIME_TEST_FOLIAGE_WAVELENGTHS_NM = float3(630.0, 532.0, 467.0);

struct PrimeCompactFoliageTestResult {
    uint mask;
    uint kind;
    PrimeRcEval referenceEvaluation;
    PrimeCompactFoliageEvaluation compactEvaluation;
    PrimeRcSampleResult referenceSample;
    PrimeRcSampleResult compactSample;
    float3 referenceDiffuse;
    float3 compactDiffuse;
    float3 referenceSpecular;
    float3 compactSpecular;
    float3 referenceDiffuseEnergy;
    float3 compactDiffuseEnergy;
    float3 referenceSpecularEnergy;
    float3 compactSpecularEnergy;
    float4 mixWeights;
    uint comparableSample;
};

float primeCompactFoliageParam(uint4 encoded[4], uint index) {
    uint flatIndex = index + 1u;
    return asfloat(encoded[flatIndex / 4u][flatIndex % 4u]);
}

float3 primeCompactFoliageDirection(float cosineTheta, float phi) {
    float radius = sqrt(max(0.0, 1.0 - cosineTheta * cosineTheta));
    return float3(radius * cos(phi), radius * sin(phi), cosineTheta);
}

bool primeCompactFoliageClose(float first, float second) {
    return primeTestClose(first, second, 6.0e-6, 4.0e-5);
}

bool primeCompactFoliageClose(float2 first, float2 second) {
    return primeCompactFoliageClose(first.x, second.x)
            && primeCompactFoliageClose(first.y, second.y);
}

bool primeCompactFoliageClose(float3 first, float3 second) {
    return primeTestClose(first, second, 6.0e-6, 4.0e-5);
}

PrimeRcVolumeStack primeCompactFoliageEmptyStack() {
    PrimeRcVolumeStack stack;
    stack.values[0].extinction = float3(0.0);
    stack.values[0].albedo = float3(0.0);
    stack.values[0].anisotropy = 0.0;
    stack.values[0].ior = 1.0;
    stack.values[1] = stack.values[0];
    stack.count = 0u;
    return stack;
}

void primeCompactFoliageCompare(
        inout PrimeCompactFoliageTestResult result,
        PrimeRcState reference,
        PrimeCompactFoliageState compact) {
    result.mask |= primeTestClassify(result.referenceEvaluation.throughput.value);
    result.mask |= primeTestClassify(result.referenceEvaluation.pdf);
    result.mask |= primeTestClassify(result.compactEvaluation.throughput.value);
    result.mask |= primeTestClassify(result.compactEvaluation.pdf);
    result.mask |= primeTestClassify(result.referenceDiffuse);
    result.mask |= primeTestClassify(result.compactDiffuse);
    result.mask |= primeTestClassify(result.referenceSpecular);
    result.mask |= primeTestClassify(result.compactSpecular);
    result.mask |= primeTestClassify(result.referenceDiffuseEnergy);
    result.mask |= primeTestClassify(result.compactDiffuseEnergy);
    result.mask |= primeTestClassify(result.referenceSpecularEnergy);
    result.mask |= primeTestClassify(result.compactSpecularEnergy);
    if (!primeCompactFoliageClose(
                result.referenceEvaluation.throughput.value,
                result.compactEvaluation.throughput.value)
            || !primeCompactFoliageClose(result.referenceDiffuse, result.compactDiffuse)
            || !primeCompactFoliageClose(result.referenceSpecular, result.compactSpecular)
            || !primeCompactFoliageClose(
                    result.referenceDiffuseEnergy, result.compactDiffuseEnergy)
            || !primeCompactFoliageClose(
                    result.referenceSpecularEnergy, result.compactSpecularEnergy)) {
        result.mask |= PRIME_TEST_FAILURE_VALUE;
    }
    if (!primeCompactFoliageClose(
                result.referenceEvaluation.pdf, result.compactEvaluation.pdf)) {
        result.mask |= PRIME_TEST_FAILURE_PDF;
    }
    if (result.referenceEvaluation.throughput.flags
            != result.compactEvaluation.throughput.flags) {
        result.mask |= PRIME_TEST_FAILURE_EVENT;
    }
    bool stateMismatch = !primeCompactFoliageClose(
            reference.specularMicrofacet.alpha, compact.base.microfacet.alpha);
    if (result.kind == 2u) {
        stateMismatch = stateMismatch
                || !primeCompactFoliageClose(
                        reference.conductorFresnel.f0,
                        compact.base.conductorFresnel.f0)
                || !primeCompactFoliageClose(
                        reference.conductorEss, compact.base.conductorEss);
    } else {
        stateMismatch = stateMismatch
            || !primeCompactFoliageClose(
                    reference.transmissionMicrofacet.alpha,
                    compact.transmissionMicrofacet.alpha)
            || !primeCompactFoliageClose(
                    reference.specularFresnel.ior,
                    compact.base.dielectricFresnel.ior)
            || !primeCompactFoliageClose(
                    reference.specularFresnel.energyIor,
                    compact.base.dielectricFresnel.energyIor)
            || !primeCompactFoliageClose(
                    reference.transmissionTint, compact.transmissionTint)
            || !primeCompactFoliageClose(
                    reference.glossyDiffuse.coatTransIn,
                    compact.base.layer.transmittanceIn)
            || !primeCompactFoliageClose(
                    reference.glossyDiffuse.subSampleWeight,
                    compact.base.layer.baseSampleWeight)
            || !primeCompactFoliageClose(
                    reference.glossyDiffuse.coatSampleWeight,
                    compact.base.layer.specularSampleWeight)
            || !primeCompactFoliageClose(
                    reference.dielectricBase.firstSampleWeight,
                    compact.dielectricMix.firstSampleWeight)
            || !primeCompactFoliageClose(
                    reference.dielectricBase.secondSampleWeight,
                    compact.dielectricMix.secondSampleWeight)
            || !primeCompactFoliageClose(
                    reference.material.weight.transmission,
                    compact.transmissionWeight);
    }
    if (stateMismatch) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }

    uint referenceFlags = result.referenceSample.bsdfSample.throughput.flags;
    uint compactFlags = result.compactSample.bsdfSample.throughput.flags;
    if (result.comparableSample != 0u && referenceFlags != compactFlags) {
        result.mask |= PRIME_TEST_FAILURE_EVENT;
    }
    if (result.comparableSample != 0u && referenceFlags != PRIME_RC_FLAG_NONE) {
        result.mask |= primeTestClassify(result.referenceSample.bsdfSample.throughput.value);
        result.mask |= primeTestClassify(result.referenceSample.bsdfSample.pdf);
        result.mask |= primeTestClassify(result.referenceSample.bsdfSample.wo);
        result.mask |= primeTestClassify(result.compactSample.bsdfSample.throughput.value);
        result.mask |= primeTestClassify(result.compactSample.bsdfSample.pdf);
        result.mask |= primeTestClassify(result.compactSample.bsdfSample.wo);
        if (!primeCompactFoliageClose(
                    result.referenceSample.bsdfSample.throughput.value,
                    result.compactSample.bsdfSample.throughput.value)
                || !primeCompactFoliageClose(
                    result.referenceSample.bsdfSample.pdf,
                    result.compactSample.bsdfSample.pdf)
                || !primeCompactFoliageClose(
                    result.referenceSample.bsdfSample.eta,
                    result.compactSample.bsdfSample.eta)
                || !primeCompactFoliageClose(
                    result.referenceSample.bsdfSample.wo,
                    result.compactSample.bsdfSample.wo)) {
            result.mask |= PRIME_TEST_FAILURE_VALUE;
        }
    }
    if (result.comparableSample != 0u
            && (result.referenceSample.volumeStack.count
                    != result.compactSample.volumeStack.count
            || !primeCompactFoliageClose(
                    result.referenceSample.rayT, result.compactSample.rayT))) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
}

PrimeCompactFoliageTestResult primeCompactFoliageRun(uint4 encoded[4]) {
    PrimeCompactFoliageTestResult result;
    result.mask = 0u;
    result.kind = encoded[0].x;
    bool conductor = result.kind == 2u;
    float roughness = primeCompactFoliageParam(encoded, 0u);
    float ior = primeCompactFoliageParam(encoded, 1u);
    float subsurfaceWeight = result.kind == 1u
            ? primeCompactFoliageParam(encoded, 2u) : 0.0;
    float3 wi = primeCompactFoliageDirection(
            abs(primeCompactFoliageParam(encoded, 3u)),
            primeCompactFoliageParam(encoded, 4u));
    float3 wo = primeCompactFoliageDirection(
            primeCompactFoliageParam(encoded, 5u),
            primeCompactFoliageParam(encoded, 6u));
    float3 randomValue = float3(
            primeCompactFoliageParam(encoded, 7u),
            primeCompactFoliageParam(encoded, 8u),
            primeCompactFoliageParam(encoded, 9u));
    float3 color = float3(
            primeCompactFoliageParam(encoded, 10u),
            primeCompactFoliageParam(encoded, 11u),
            primeCompactFoliageParam(encoded, 12u));
    float inverseOutsideIor = primeCompactFoliageParam(encoded, 13u);
    float rayT = primeCompactFoliageParam(encoded, 14u);

    PrimeRcMaterial material = primeRcMaterialFromMetallic(
            color, roughness, conductor ? 1.0 : 0.0, float3(0.0, 0.0, 1.0));
    if (!conductor) {
        material.specular.ior = ior;
        material.weight.subsurface = subsurfaceWeight;
        material.subsurface.color = color;
    }
    material.weight.transmission = PRIME_TEST_FOLIAGE_TRANSMISSION_WEIGHT;
    material.geometry.thinWalled = 1u;
    material.geometry.thickness = 0.0625;
    material.transmission.color = clamp(color, float3(0.02), float3(1.0));
    material.transmission.depth = 0.0;
    material.transmission.scatter = float3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    if (!primeCompactFoliageSupports(material)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    PrimeRcMaterial unsupported = material;
    unsupported.weight.coat = 0.5;
    if (primeCompactFoliageSupports(unsupported)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }

    PrimeRcState reference = primeRcPrimeThinWallStateInit(
            material,
            wi,
            inverseOutsideIor,
            rayT,
            PRIME_TEST_FOLIAGE_WAVELENGTHS_NM,
            0u,
            PRIME_RC_DETAIL_DEFAULT,
            0u);
    PrimeCompactFoliageState compact = primeCompactFoliageStateInit(
            primeTransmissionGgxEnergy(), material, wi, inverseOutsideIor, rayT);
    result.mixWeights = float4(
            reference.dielectricBase.firstSampleWeight,
            reference.dielectricBase.secondSampleWeight,
            compact.dielectricMix.firstSampleWeight,
            compact.dielectricMix.secondSampleWeight);
    result.comparableSample = conductor
            || (abs(randomValue.z - reference.dielectricBase.secondSampleWeight) > 1.0e-6
                    && abs(randomValue.z
                            - compact.dielectricMix.secondSampleWeight) > 1.0e-6)
            ? 1u : 0u;
    PrimeRcVolumeStack stack = primeCompactFoliageEmptyStack();

    reference.samplingFlags = PRIME_RC_FLAG_ALL;
    result.referenceEvaluation = primeRcPrimeThinWallEvaluate(wi, wo, reference);
    result.compactEvaluation = primeCompactFoliageEvaluate(wi, wo, compact);
    result.referenceSample = primeRcPrimeThinWallSample(
            wi, randomValue, reference, stack);
    result.compactSample = primeCompactFoliageSample(
            wi, randomValue, compact, stack);

    PrimeRcState referenceDiffuse = reference;
    referenceDiffuse.samplingFlags = PRIME_RC_FLAG_DIFFUSE;
    PrimeRcState referenceSpecular = reference;
    referenceSpecular.samplingFlags = PRIME_RC_FLAG_SPECULAR | PRIME_RC_FLAG_DELTA;
    result.referenceDiffuse = primeRcPrimeThinWallEval(
            wi, wo, referenceDiffuse).value;
    result.referenceSpecular = primeRcPrimeThinWallEval(
            wi, wo, referenceSpecular).value;
    PrimeCompactFoliageComponents components = primeCompactFoliageEvaluateComponents(
            wi, wo, compact);
    result.compactDiffuse = components.diffuse;
    result.compactSpecular = components.specular;
    if (!primeCompactFoliageClose(result.referenceEvaluation.pdf, components.pdf)) {
        result.mask |= PRIME_TEST_FAILURE_PDF;
    }

    result.referenceDiffuseEnergy = primeRcBaseSubstrateEnergy(
            primeTransmissionGgxEnergy(), wi, referenceDiffuse);
    result.referenceSpecularEnergy = primeRcBaseSubstrateEnergy(
            primeTransmissionGgxEnergy(), wi, referenceSpecular);
    PrimeCompactFoliageEnergy energy = primeCompactFoliageEnergy(
            primeTransmissionGgxEnergy(), wi, compact);
    result.compactDiffuseEnergy = energy.diffuse;
    result.compactSpecularEnergy = energy.specular;
    primeCompactFoliageCompare(result, reference, compact);
    return result;
}

void primeCompactFoliageWrite(uint caseIndex, PrimeCompactFoliageTestResult result) {
    uint outputOffset = primeTestOutputOffset(caseIndex, primeTestOutputWords());
    primeTestOutput[outputOffset] = uint4(result.mask, result.kind, caseIndex, 0u);
    if (!primeTestWitnessEnabled()) { return; }
    primeTestOutput[outputOffset + 1u] = asuint(float4(
            result.referenceEvaluation.throughput.value,
            result.referenceEvaluation.pdf));
    primeTestOutput[outputOffset + 2u] = asuint(float4(
            result.compactEvaluation.throughput.value,
            result.compactEvaluation.pdf));
    primeTestOutput[outputOffset + 3u] = asuint(float4(
            result.referenceSample.bsdfSample.throughput.value,
            result.referenceSample.bsdfSample.pdf));
    primeTestOutput[outputOffset + 4u] = asuint(float4(
            result.compactSample.bsdfSample.throughput.value,
            result.compactSample.bsdfSample.pdf));
    primeTestOutput[outputOffset + 5u] = asuint(float4(
            result.referenceSample.bsdfSample.wo,
            result.referenceSample.bsdfSample.eta));
    primeTestOutput[outputOffset + 6u] = asuint(float4(
            result.compactSample.bsdfSample.wo,
            result.compactSample.bsdfSample.eta));
    primeTestOutput[outputOffset + 7u] = asuint(float4(
            result.referenceEvaluation.pdf,
            result.compactEvaluation.pdf,
            result.referenceSample.bsdfSample.pdf,
            result.compactSample.bsdfSample.pdf));
    primeTestOutput[outputOffset + 8u] = asuint(result.mixWeights);
    primeTestOutput[outputOffset + 9u] = asuint(float4(result.referenceSpecular, 0.0));
    primeTestOutput[outputOffset + 10u] = asuint(float4(result.compactSpecular, 0.0));
    primeTestOutput[outputOffset + 11u] = uint4(
            result.referenceSample.bsdfSample.throughput.flags,
            result.compactSample.bsdfSample.throughput.flags,
            result.referenceSample.volumeStack.count,
            result.compactSample.volumeStack.count);
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) { return; }
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_COMPACT_FOLIAGE_INPUT_WORDS);
    uint4 encoded[4];
    for (uint word = 0u; word < PRIME_COMPACT_FOLIAGE_INPUT_WORDS; word++) {
        encoded[word] = primeTestInput[inputOffset + word];
    }
    primeCompactFoliageWrite(caseIndex, primeCompactFoliageRun(encoded));
}
