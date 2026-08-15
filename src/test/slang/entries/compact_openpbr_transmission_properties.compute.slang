#include "core/prime_test_abi.slang"
import "bsdf/adapter/prime_bsdf_specializations.slang";
import "bsdf/adapter/transmission_ggx_energy.slang";
import "bsdf/compact/openpbr_transmission.slang";
import "bsdf/core/robocute_closures.slang";
import "bsdf/core/robocute_common.slang";
import "bsdf/core/robocute_openpbr.slang";

static const uint PRIME_COMPACT_TRANSMISSION_INPUT_WORDS = 4u;
static const uint PRIME_COMPACT_TRANSMISSION_WITNESS_WORDS = 12u;
static const float3 PRIME_TEST_TRANSMISSION_WAVELENGTHS_NM =
        float3(630.0, 532.0, 467.0);

struct PrimeCompactTransmissionTestResult {
    uint mask;
    uint kind;
    PrimeRcEval referenceEvaluation;
    PrimeCompactTransmissionEvaluation compactEvaluation;
    PrimeRcSampleResult referenceSample;
    PrimeRcSampleResult compactSample;
    float3 referenceEnergy;
    float3 compactEnergy;
};

float primeCompactTransmissionParam(uint4 encoded[4], uint index) {
    uint flatIndex = index + 1u;
    return asfloat(encoded[flatIndex / 4u][flatIndex % 4u]);
}

float3 primeCompactTransmissionDirection(float cosineTheta, float phi) {
    float radius = sqrt(max(0.0, 1.0 - cosineTheta * cosineTheta));
    return float3(radius * cos(phi), radius * sin(phi), cosineTheta);
}

bool primeCompactTransmissionClose(float first, float second) {
    return primeTestClose(first, second, 5.0e-6, 3.0e-5);
}

bool primeCompactTransmissionClose(float2 first, float2 second) {
    return primeTestClose(first.x, second.x, 5.0e-6, 3.0e-5)
            && primeTestClose(first.y, second.y, 5.0e-6, 3.0e-5);
}

bool primeCompactTransmissionClose(float3 first, float3 second) {
    return primeTestClose(first, second, 5.0e-6, 3.0e-5);
}

PrimeRcVolumeStack primeCompactTransmissionStack(
        bool exiting,
        PrimeRcMaterial material) {
    PrimeRcVolumeStack stack;
    PrimeRcVolume air;
    air.extinction = float3(0.0);
    air.albedo = float3(0.0);
    air.anisotropy = 0.0;
    air.ior = 1.0;
    stack.values[0] = air;
    stack.values[1] = air;
    stack.count = 0u;
    if (exiting) {
        PrimeRcVolume volume = primeRcVolumeFromTransmission(material.transmission);
        volume.ior = material.specular.ior;
        stack.values[0] = volume;
        stack.count = 1u;
    }
    return stack;
}

void primeCompactTransmissionCompare(
        inout PrimeCompactTransmissionTestResult result,
        PrimeRcState reference,
        PrimeCompactTransmissionState compact) {
    result.mask |= primeTestClassify(result.referenceEvaluation.throughput.value);
    result.mask |= primeTestClassify(result.referenceEvaluation.pdf);
    result.mask |= primeTestClassify(result.compactEvaluation.throughput.value);
    result.mask |= primeTestClassify(result.compactEvaluation.pdf);
    result.mask |= primeTestClassify(result.referenceEnergy);
    result.mask |= primeTestClassify(result.compactEnergy);
    if (!primeCompactTransmissionClose(
                result.referenceEvaluation.throughput.value,
                result.compactEvaluation.throughput.value)
            || !primeCompactTransmissionClose(
                result.referenceEnergy, result.compactEnergy)) {
        result.mask |= PRIME_TEST_FAILURE_VALUE;
    }
    if (!primeCompactTransmissionClose(
                result.referenceEvaluation.pdf,
                result.compactEvaluation.pdf)) {
        result.mask |= PRIME_TEST_FAILURE_PDF;
    }
    if (result.referenceEvaluation.throughput.flags
            != result.compactEvaluation.throughput.flags) {
        result.mask |= PRIME_TEST_FAILURE_EVENT;
    }
    if (!primeCompactTransmissionClose(
                reference.specularMicrofacet.alpha,
                compact.specularMicrofacet.alpha)
            || !primeCompactTransmissionClose(
                reference.transmissionMicrofacet.alpha,
                compact.transmissionMicrofacet.alpha)
            || !primeCompactTransmissionClose(
                reference.specularFresnel.ior,
                compact.specularFresnel.ior)
            || !primeCompactTransmissionClose(
                reference.specularFresnel.energyIor,
                compact.specularFresnel.energyIor)
            || !primeCompactTransmissionClose(
                reference.specularFresnel.color,
                compact.specularFresnel.color)
            || !primeCompactTransmissionClose(
                reference.transmissionTint,
                compact.transmissionTint)
            || !primeCompactTransmissionClose(
                reference.transmissionVolume.extinction,
                compact.transmissionVolume.extinction)
            || !primeCompactTransmissionClose(
                reference.transmissionMultipleScattering,
                compact.transmissionMultipleScattering)
            || reference.geometryThinWalled != compact.geometryThinWalled
            || reference.entering != compact.entering
            || !primeCompactTransmissionClose(reference.rayT, compact.rayT)
            || !primeCompactTransmissionClose(reference.originalIor, compact.originalIor)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }

    uint referenceFlags = result.referenceSample.bsdfSample.throughput.flags;
    uint compactFlags = result.compactSample.bsdfSample.throughput.flags;
    if (referenceFlags != compactFlags) {
        result.mask |= PRIME_TEST_FAILURE_EVENT;
    }
    if (referenceFlags != PRIME_RC_FLAG_NONE) {
        result.mask |= primeTestClassify(result.referenceSample.bsdfSample.throughput.value);
        result.mask |= primeTestClassify(result.referenceSample.bsdfSample.pdf);
        result.mask |= primeTestClassify(result.referenceSample.bsdfSample.eta);
        result.mask |= primeTestClassify(result.referenceSample.bsdfSample.wo);
        result.mask |= primeTestClassify(result.compactSample.bsdfSample.throughput.value);
        result.mask |= primeTestClassify(result.compactSample.bsdfSample.pdf);
        result.mask |= primeTestClassify(result.compactSample.bsdfSample.eta);
        result.mask |= primeTestClassify(result.compactSample.bsdfSample.wo);
        if (!primeCompactTransmissionClose(
                    result.referenceSample.bsdfSample.throughput.value,
                    result.compactSample.bsdfSample.throughput.value)
                || !primeCompactTransmissionClose(
                    result.referenceSample.bsdfSample.pdf,
                    result.compactSample.bsdfSample.pdf)
                || !primeCompactTransmissionClose(
                    result.referenceSample.bsdfSample.eta,
                    result.compactSample.bsdfSample.eta)
                || !primeCompactTransmissionClose(
                    result.referenceSample.bsdfSample.wo,
                    result.compactSample.bsdfSample.wo)) {
            result.mask |= PRIME_TEST_FAILURE_VALUE;
        }
    }
    if (result.referenceSample.volumeStack.count != result.compactSample.volumeStack.count
            || !primeCompactTransmissionClose(
                    result.referenceSample.rayT, result.compactSample.rayT)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    for (uint index = 0u; index < result.referenceSample.volumeStack.count; index++) {
        PrimeRcVolume referenceVolume = result.referenceSample.volumeStack.values[index];
        PrimeRcVolume compactVolume = result.compactSample.volumeStack.values[index];
        if (!primeCompactTransmissionClose(
                    referenceVolume.extinction, compactVolume.extinction)
                || !primeCompactTransmissionClose(referenceVolume.albedo, compactVolume.albedo)
                || !primeCompactTransmissionClose(
                    referenceVolume.anisotropy, compactVolume.anisotropy)
                || !primeCompactTransmissionClose(referenceVolume.ior, compactVolume.ior)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
    }
}

PrimeCompactTransmissionTestResult primeCompactTransmissionRun(uint4 encoded[4]) {
    PrimeCompactTransmissionTestResult result;
    result.mask = 0u;
    result.kind = encoded[0].x;
    uint topology = result.kind % 3u;
    uint samplingMode = result.kind / 3u;
    bool thinWalled = topology == 2u;
    bool exiting = topology == 1u;
    uint samplingFlags = samplingMode == 0u
            ? PRIME_RC_FLAG_ALL
            : samplingMode == 1u ? PRIME_RC_FLAG_REFLECTION : PRIME_RC_FLAG_TRANSMISSION;
    float roughness = primeCompactTransmissionParam(encoded, 0u);
    float ior = primeCompactTransmissionParam(encoded, 1u);
    float wiCosine = abs(primeCompactTransmissionParam(encoded, 2u));
    if (exiting) { wiCosine = -wiCosine; }
    float3 wi = primeCompactTransmissionDirection(
            wiCosine, primeCompactTransmissionParam(encoded, 3u));
    float3 wo = primeCompactTransmissionDirection(
            primeCompactTransmissionParam(encoded, 4u),
            primeCompactTransmissionParam(encoded, 5u));
    float3 randomValue = float3(
            primeCompactTransmissionParam(encoded, 6u),
            primeCompactTransmissionParam(encoded, 7u),
            primeCompactTransmissionParam(encoded, 8u));
    float3 color = float3(
            primeCompactTransmissionParam(encoded, 9u),
            primeCompactTransmissionParam(encoded, 10u),
            primeCompactTransmissionParam(encoded, 11u));
    float inverseOutsideIor = primeCompactTransmissionParam(encoded, 12u);
    float rayT = primeCompactTransmissionParam(encoded, 13u);
    bool thinPhysicalExit = thinWalled
            && primeCompactTransmissionParam(encoded, 14u) >= 0.5;

    PrimeRcMaterial material = primeRcMaterialFromMetallic(
            float3(1.0), roughness, 0.0, float3(0.0, 0.0, 1.0));
    material.weight.transmission = 1.0;
    material.geometry.thinWalled = thinWalled ? 1u : 0u;
    material.geometry.thickness = thinWalled ? 0.0625 : 1.0;
    material.specular.ior = ior;
    material.transmission.color = color;
    material.transmission.depth = thinWalled ? 0.0 : 1.0;
    material.transmission.scatter = float3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    if (!primeCompactTransmissionSupports(material)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    PrimeRcMaterial unsupported = material;
    unsupported.transmission.dispersionScale = 0.5;
    if (primeCompactTransmissionSupports(unsupported)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }

    PrimeRcVolumeStack stack = primeCompactTransmissionStack(exiting, material);
    PrimeRcState reference = primeRcTransmissionStateInit(
            primeTransmissionGgxEnergy(),
            material,
            wi,
            inverseOutsideIor,
            rayT,
            PRIME_TEST_TRANSMISSION_WAVELENGTHS_NM,
            0u,
            PRIME_RC_DETAIL_DEFAULT,
            0u);
    reference = primeRcPrimeTransmissionInterfaceState(wi, reference);
    PrimeCompactTransmissionState compact = primeCompactTransmissionStateInit(
            primeTransmissionGgxEnergy(),
            material,
            wi,
            inverseOutsideIor,
            rayT);
    compact = primeCompactTransmissionInterfaceState(
            primeTransmissionGgxEnergy(), wi, compact);
    if (thinPhysicalExit) {
        reference.entering = 0u;
        compact.entering = 0u;
    }

    reference.samplingFlags = samplingFlags;
    result.referenceEvaluation = primeRcPrimeTransmissionEvaluate(wi, wo, reference);
    result.compactEvaluation = primeCompactTransmissionEvaluate(
            wi, wo, compact, samplingFlags);
    result.referenceSample = primeRcPrimeTransmissionSample(
            wi, randomValue, reference, stack);
    result.compactSample = primeCompactTransmissionSample(
            wi, randomValue, compact, samplingFlags, stack);
    result.referenceEnergy = primeRcTransmissionEnergy(
            primeTransmissionGgxEnergy(), wi, reference);
    result.compactEnergy = primeCompactTransmissionEnergy(
            primeTransmissionGgxEnergy(), wi, compact, samplingFlags);
    primeCompactTransmissionCompare(result, reference, compact);
    return result;
}

void primeCompactTransmissionWrite(
        uint caseIndex,
        PrimeCompactTransmissionTestResult result) {
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
    primeTestOutput[outputOffset + 7u] = uint4(
            result.referenceSample.bsdfSample.throughput.flags,
            result.compactSample.bsdfSample.throughput.flags,
            result.referenceSample.volumeStack.count,
            result.compactSample.volumeStack.count);
    primeTestOutput[outputOffset + 8u] = asuint(float4(result.referenceEnergy, 0.0));
    primeTestOutput[outputOffset + 9u] = asuint(float4(result.compactEnergy, 0.0));
    primeTestOutput[outputOffset + 10u] = asuint(float4(
            result.referenceSample.rayT,
            result.compactSample.rayT,
            0.0,
            0.0));
    primeTestOutput[outputOffset + 11u] = uint4(0u);
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) { return; }
    uint inputOffset = primeTestInputOffset(
            caseIndex, PRIME_COMPACT_TRANSMISSION_INPUT_WORDS);
    uint4 encoded[4];
    for (uint word = 0u; word < PRIME_COMPACT_TRANSMISSION_INPUT_WORDS; word++) {
        encoded[word] = primeTestInput[inputOffset + word];
    }
    primeCompactTransmissionWrite(
            caseIndex, primeCompactTransmissionRun(encoded));
}
