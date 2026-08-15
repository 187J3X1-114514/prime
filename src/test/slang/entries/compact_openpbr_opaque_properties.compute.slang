#include "core/prime_test_abi.slang"
import "bsdf/adapter/prime_bsdf_specializations.slang";
import "bsdf/adapter/transmission_ggx_energy.slang";
import "bsdf/compact/openpbr_opaque.slang";
import "bsdf/core/robocute_closures.slang";
import "bsdf/core/robocute_common.slang";
import "bsdf/core/robocute_openpbr.slang";

static const uint PRIME_COMPACT_INPUT_WORDS = 4u;
static const uint PRIME_COMPACT_WITNESS_WORDS = 12u;

struct PrimeCompactTestResult {
    uint mask;
    uint kind;
    PrimeRcEval referenceEval;
    PrimeCompactOpaqueEvaluation compactEval;
    float3 referenceDiffuse;
    float3 referenceSpecular;
    PrimeCompactOpaqueComponents compactComponents;
    PrimeRcSampleResult referenceSample;
    PrimeRcSampleResult compactSample;
    float3 referenceEnergy;
    PrimeCompactOpaqueEnergy compactEnergy;
};

float primeCompactParam(uint4 encoded[4], uint index) {
    uint flatIndex = index + 1u;
    return asfloat(encoded[flatIndex / 4u][flatIndex % 4u]);
}

float3 primeCompactDirection(float cosineTheta, float phi) {
    float radius = sqrt(max(0.0, 1.0 - cosineTheta * cosineTheta));
    return float3(radius * cos(phi), radius * sin(phi), cosineTheta);
}

PrimeRcVolumeStack primeCompactEmptyStack() {
    PrimeRcVolumeStack stack;
    PrimeRcVolume air;
    air.extinction = float3(0.0);
    air.albedo = float3(0.0);
    air.anisotropy = 0.0;
    air.ior = 1.0;
    stack.values[0] = air;
    stack.values[1] = air;
    stack.count = 0u;
    return stack;
}

bool primeCompactNonnegative(float value) {
    return value >= 0.0 && primeTestClassify(value) == 0u;
}

bool primeCompactNonnegative(float3 value) {
    return all(value >= float3(0.0)) && primeTestClassify(value) == 0u;
}

bool primeCompactClose(float first, float second) {
    return primeTestClose(first, second, 4.0e-6, 2.0e-5);
}

bool primeCompactClose(float3 first, float3 second) {
    return primeTestClose(first, second, 4.0e-6, 2.0e-5);
}

PrimeRcState primeCompactReferenceState(
        uint kind,
        PrimeRcMaterial material,
        float3 wi,
        float inverseOutsideIor,
        float rayT) {
    return kind >= PRIME_COMPACT_OPAQUE_SUBSURFACE
            ? primeRcSubsurfaceGlossyStateInit(
                    primeTransmissionGgxEnergy(),
                    material,
                    wi,
                    inverseOutsideIor,
                    rayT,
                    float3(630.0, 532.0, 467.0),
                    0u,
                    PRIME_RC_DETAIL_DEFAULT,
                    0u)
            : primeRcBasicMetallicStateInit(
                    primeTransmissionGgxEnergy(),
                    material,
                    wi,
                    inverseOutsideIor,
                    rayT,
                    float3(630.0, 532.0, 467.0),
                    0u,
                    PRIME_RC_DETAIL_DEFAULT,
                    0u);
}

PrimeRcEval primeCompactReferenceEvaluate(
        uint kind, float3 wi, float3 wo, PrimeRcState state) {
    return kind >= PRIME_COMPACT_OPAQUE_SUBSURFACE
            ? primeRcPrimeSubsurfaceGlossyEvaluate(wi, wo, state)
            : primeRcPrimeBasicMetallicEvaluate(wi, wo, state);
}

PrimeRcThroughput primeCompactReferenceEvalWithFlags(
        uint kind, float3 wi, float3 wo, PrimeRcState state, uint flags) {
    state.samplingFlags = flags;
    return kind >= PRIME_COMPACT_OPAQUE_SUBSURFACE
            ? primeRcPrimeSubsurfaceGlossyEval(wi, wo, state)
            : primeRcPrimeBasicMetallicEval(wi, wo, state);
}

PrimeRcSampleResult primeCompactReferenceSample(
        uint kind,
        float3 wi,
        float3 randomValue,
        PrimeRcState state,
        PrimeRcVolumeStack stack) {
    return kind >= PRIME_COMPACT_OPAQUE_SUBSURFACE
            ? primeRcSubsurfaceGlossySample(wi, randomValue, state, stack)
            : primeRcBasicMetallicSample(wi, randomValue, state, stack);
}

float3 primeCompactReferenceEnergy(uint kind, float3 wi, PrimeRcState state) {
    return kind >= PRIME_COMPACT_OPAQUE_SUBSURFACE
            ? primeRcSubsurfaceGlossyEnergy(wi, state)
            : primeRcBasicMetallicEnergy(wi, state);
}

void primeCompactCompare(inout PrimeCompactTestResult result) {
    result.mask |= primeTestClassify(result.referenceEval.throughput.value);
    result.mask |= primeTestClassify(result.referenceEval.pdf);
    result.mask |= primeTestClassify(result.compactEval.throughput.value);
    result.mask |= primeTestClassify(result.compactEval.pdf);
    result.mask |= primeTestClassify(result.compactComponents.diffuse);
    result.mask |= primeTestClassify(result.compactComponents.specular);
    result.mask |= primeTestClassify(result.compactComponents.pdf);
    result.mask |= primeTestClassify(result.referenceEnergy);
    result.mask |= primeTestClassify(result.compactEnergy.diffuse);
    result.mask |= primeTestClassify(result.compactEnergy.specular);

    if (!primeCompactNonnegative(result.compactEval.throughput.value)
            || !primeCompactNonnegative(result.compactEval.pdf)
            || !primeCompactNonnegative(result.compactComponents.diffuse)
            || !primeCompactNonnegative(result.compactComponents.specular)
            || !primeCompactNonnegative(result.compactComponents.pdf)
            || !primeCompactNonnegative(result.compactEnergy.diffuse)
            || !primeCompactNonnegative(result.compactEnergy.specular)) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    if (!primeCompactClose(
                result.referenceEval.throughput.value,
                result.compactEval.throughput.value)
            || !primeCompactClose(
                result.referenceDiffuse,
                result.compactComponents.diffuse)
            || !primeCompactClose(
                result.referenceSpecular,
                result.compactComponents.specular)
            || !primeCompactClose(
                result.referenceEnergy,
                result.compactEnergy.diffuse + result.compactEnergy.specular)) {
        result.mask |= PRIME_TEST_FAILURE_VALUE;
    }
    if (!primeCompactClose(result.referenceEval.pdf, result.compactEval.pdf)
            || !primeCompactClose(result.referenceEval.pdf, result.compactComponents.pdf)) {
        result.mask |= PRIME_TEST_FAILURE_PDF;
    }
    if (result.referenceEval.throughput.flags != result.compactEval.throughput.flags) {
        result.mask |= PRIME_TEST_FAILURE_EVENT;
    }

    if (result.referenceSample.bsdfSample.throughput.flags
            != result.compactSample.bsdfSample.throughput.flags) {
        result.mask |= PRIME_TEST_FAILURE_EVENT;
    }
    if (result.referenceSample.bsdfSample.throughput.flags != PRIME_RC_FLAG_NONE) {
        result.mask |= primeTestClassify(result.referenceSample.bsdfSample.throughput.value);
        result.mask |= primeTestClassify(result.referenceSample.bsdfSample.pdf);
        result.mask |= primeTestClassify(result.compactSample.bsdfSample.throughput.value);
        result.mask |= primeTestClassify(result.compactSample.bsdfSample.pdf);
        if (!primeCompactClose(
                    result.referenceSample.bsdfSample.throughput.value,
                    result.compactSample.bsdfSample.throughput.value)) {
            result.mask |= PRIME_TEST_FAILURE_VALUE;
        }
        if (!primeCompactClose(
                    result.referenceSample.bsdfSample.pdf,
                    result.compactSample.bsdfSample.pdf)) {
            result.mask |= PRIME_TEST_FAILURE_PDF;
        }
        if (!primeCompactClose(
                    result.referenceSample.bsdfSample.wo,
                    result.compactSample.bsdfSample.wo)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
    }
    if (result.referenceSample.volumeStack.count != result.compactSample.volumeStack.count
            || !primeCompactClose(result.referenceSample.rayT, result.compactSample.rayT)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
}

PrimeCompactTestResult primeCompactRun(uint4 encoded[4]) {
    PrimeCompactTestResult result;
    result.mask = 0u;
    result.kind = encoded[0].x;
    float roughness = primeCompactParam(encoded, 0u);
    float ior = primeCompactParam(encoded, 1u);
    float3 wi = primeCompactDirection(
            primeCompactParam(encoded, 2u), primeCompactParam(encoded, 3u));
    float3 wo = primeCompactDirection(
            primeCompactParam(encoded, 4u), primeCompactParam(encoded, 5u));
    float3 randomValue = float3(
            primeCompactParam(encoded, 6u),
            primeCompactParam(encoded, 7u),
            primeCompactParam(encoded, 8u));
    float3 color = float3(
            primeCompactParam(encoded, 9u),
            primeCompactParam(encoded, 10u),
            primeCompactParam(encoded, 11u));
    float anisotropy = primeCompactParam(encoded, 12u);
    float inverseOutsideIor = primeCompactParam(encoded, 13u);
    float rayT = primeCompactParam(encoded, 14u);

    PrimeRcMaterial material = primeRcMaterialFromMetallic(
            color,
            roughness,
            result.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR ? 1.0 : 0.0,
            float3(0.0, 0.0, 1.0));
    material.specular.ior = ior;
    if (result.kind >= PRIME_COMPACT_OPAQUE_SUBSURFACE) {
        material.weight.subsurface = 1.0;
        material.subsurface.color = color;
        material.subsurface.scatterAnisotropy = anisotropy;
        material.geometry.thinWalled = result.kind == 3u ? 1u : 0u;
    }
    if (!primeCompactOpaqueSupports(material)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }

    PrimeRcVolumeStack stack = primeCompactEmptyStack();
    PrimeRcState reference = primeCompactReferenceState(
            result.kind, material, wi, inverseOutsideIor, rayT);
    PrimeCompactOpaqueState compact = primeCompactOpaqueStateInit(
            primeTransmissionGgxEnergy(), material, wi, inverseOutsideIor, rayT);
    result.referenceEval = primeCompactReferenceEvaluate(result.kind, wi, wo, reference);
    result.compactEval = primeCompactOpaqueEvaluate(wi, wo, compact);
    result.referenceDiffuse = primeCompactReferenceEvalWithFlags(
            result.kind, wi, wo, reference, PRIME_RC_FLAG_DIFFUSE).value;
    result.referenceSpecular = primeCompactReferenceEvalWithFlags(
            result.kind,
            wi,
            wo,
            reference,
            PRIME_RC_FLAG_SPECULAR | PRIME_RC_FLAG_DELTA).value;
    result.compactComponents = primeCompactOpaqueEvaluateComponents(wi, wo, compact);
    result.referenceSample = primeCompactReferenceSample(
            result.kind, wi, randomValue, reference, stack);
    result.compactSample = primeCompactOpaqueSample(wi, randomValue, compact, stack);
    result.referenceEnergy = primeCompactReferenceEnergy(result.kind, wi, reference);
    result.compactEnergy = primeCompactOpaqueEnergy(wi, compact);
    primeCompactCompare(result);
    return result;
}

void primeCompactWrite(uint caseIndex, PrimeCompactTestResult result) {
    uint outputOffset = primeTestOutputOffset(caseIndex, primeTestOutputWords());
    primeTestOutput[outputOffset] = uint4(result.mask, result.kind, caseIndex, 0u);
    if (!primeTestWitnessEnabled()) { return; }
    primeTestOutput[outputOffset + 1u] = asuint(float4(
            result.referenceEval.throughput.value, result.referenceEval.pdf));
    primeTestOutput[outputOffset + 2u] = asuint(float4(
            result.compactEval.throughput.value, result.compactEval.pdf));
    primeTestOutput[outputOffset + 3u] = asuint(float4(result.referenceDiffuse, 0.0));
    primeTestOutput[outputOffset + 4u] = asuint(float4(result.compactComponents.diffuse, 0.0));
    primeTestOutput[outputOffset + 5u] = asuint(float4(result.referenceSpecular, 0.0));
    primeTestOutput[outputOffset + 6u] = asuint(float4(result.compactComponents.specular, 0.0));
    primeTestOutput[outputOffset + 7u] = asuint(float4(
            result.referenceSample.bsdfSample.throughput.value,
            result.referenceSample.bsdfSample.pdf));
    primeTestOutput[outputOffset + 8u] = asuint(float4(
            result.compactSample.bsdfSample.throughput.value,
            result.compactSample.bsdfSample.pdf));
    primeTestOutput[outputOffset + 9u] = asuint(float4(
            result.referenceSample.bsdfSample.wo,
            float(result.referenceSample.bsdfSample.throughput.flags)));
    primeTestOutput[outputOffset + 10u] = asuint(float4(
            result.compactSample.bsdfSample.wo,
            float(result.compactSample.bsdfSample.throughput.flags)));
    primeTestOutput[outputOffset + 11u] = asuint(float4(
            result.referenceEnergy,
            max(abs(result.referenceEval.pdf - result.compactEval.pdf), 0.0)));
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) { return; }
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_COMPACT_INPUT_WORDS);
    uint4 encoded[4];
    for (uint word = 0u; word < PRIME_COMPACT_INPUT_WORDS; word++) {
        encoded[word] = primeTestInput[inputOffset + word];
    }
    primeCompactWrite(caseIndex, primeCompactRun(encoded));
}
