#include "core/prime_test_abi.slang"
import "bsdf/adapter/transmission_ggx_energy.slang";
import "bsdf/compact/openpbr_common.slang";
import "bsdf/compact/openpbr_foliage.slang";
import "bsdf/compact/openpbr_material.slang";

static const uint PRIME_COMPACT_FOLIAGE_INPUT_WORDS = 4u;
static const float PRIME_TEST_FOLIAGE_TRANSMISSION_WEIGHT = 0.15;

struct PrimeCompactFoliageTestResult {
    uint mask;
    uint kind;
    PrimeCompactFoliageEvaluation evaluation;
    PrimeCompactFoliageEvaluation sampledEvaluation;
    PrimeCompactFoliageComponents components;
    PrimeCompactFoliageEnergy energy;
    PrimeOpenPbrSampleResult sample;
};

float primeCompactFoliageParam(uint4 encoded[4], uint index) {
    uint flatIndex = index + 1u;
    return asfloat(encoded[flatIndex / 4u][flatIndex % 4u]);
}

float3 primeCompactFoliageDirection(float cosineTheta, float phi) {
    float radius = sqrt(max(0.0, 1.0 - cosineTheta * cosineTheta));
    return float3(radius * cos(phi), radius * sin(phi), cosineTheta);
}

PrimeOpenPbrVolumeStack primeCompactFoliageEmptyStack() {
    PrimeOpenPbrVolumeStack stack;
    stack.values[0].extinction = float3(0.0);
    stack.values[0].albedo = float3(0.0);
    stack.values[0].anisotropy = 0.0;
    stack.values[0].ior = 1.0;
    stack.values[1] = stack.values[0];
    stack.count = 0u;
    return stack;
}

void primeCompactFoliageClassify(inout uint mask, float value) {
    mask |= primeTestClassify(value);
}

void primeCompactFoliageClassify(inout uint mask, float2 value) {
    mask |= primeTestClassify(value);
}

void primeCompactFoliageClassify(inout uint mask, float3 value) {
    mask |= primeTestClassify(value);
}

bool primeCompactFoliageNonnegative(float value) {
    return value >= 0.0 && primeTestClassify(value) == 0u;
}

bool primeCompactFoliageNonnegative(float3 value) {
    return all(value >= float3(0.0)) && primeTestClassify(value) == 0u;
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

    PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
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
    PrimeOpenPbrMaterial unsupported = material;
    unsupported.weight.coat = 0.5;
    if (primeCompactFoliageSupports(unsupported)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }

    PrimeCompactFoliageState state = primeCompactFoliageStateInit(
            primeTransmissionGgxEnergy(), material, wi, inverseOutsideIor, rayT);
    result.evaluation = primeCompactFoliageEvaluate(wi, wo, state);
    result.components = primeCompactFoliageEvaluateComponents(wi, wo, state);
    result.energy = primeCompactFoliageEnergy(
            primeTransmissionGgxEnergy(), wi, state);
    result.sample = primeCompactFoliageSample(
            wi, randomValue, state, primeCompactFoliageEmptyStack());
    result.sampledEvaluation.throughput = primeOpenPbrZeroThroughput();
    result.sampledEvaluation.pdf = 0.0;

    primeCompactFoliageClassify(result.mask, state.base.microfacet.alpha);
    primeCompactFoliageClassify(result.mask, result.evaluation.throughput.value);
    primeCompactFoliageClassify(result.mask, result.evaluation.pdf);
    primeCompactFoliageClassify(result.mask, result.components.diffuse);
    primeCompactFoliageClassify(result.mask, result.components.specular);
    primeCompactFoliageClassify(result.mask, result.components.pdf);
    primeCompactFoliageClassify(result.mask, result.energy.diffuse);
    primeCompactFoliageClassify(result.mask, result.energy.specular);
    if (!primeCompactFoliageNonnegative(result.evaluation.throughput.value)
            || !primeCompactFoliageNonnegative(result.evaluation.pdf)
            || !primeCompactFoliageNonnegative(result.components.diffuse)
            || !primeCompactFoliageNonnegative(result.components.specular)
            || !primeCompactFoliageNonnegative(result.components.pdf)
            || !primeCompactFoliageNonnegative(result.energy.diffuse)
            || !primeCompactFoliageNonnegative(result.energy.specular)) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    if (!primeTestClose(
                result.evaluation.throughput.value,
                result.components.diffuse + result.components.specular,
                6.0e-6,
                4.0e-5)
            || !primeTestClose(
                result.evaluation.pdf, result.components.pdf, 6.0e-6, 4.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_VALUE;
    }

    uint flags = result.sample.bsdfSample.throughput.flags;
    if (flags != PRIME_OPENPBR_FLAG_NONE) {
        primeCompactFoliageClassify(
                result.mask, result.sample.bsdfSample.throughput.value);
        primeCompactFoliageClassify(result.mask, result.sample.bsdfSample.pdf);
        primeCompactFoliageClassify(result.mask, result.sample.bsdfSample.eta);
        primeCompactFoliageClassify(result.mask, result.sample.bsdfSample.wo);
        if (!primeCompactFoliageNonnegative(result.sample.bsdfSample.throughput.value)
                || !primeCompactFoliageNonnegative(result.sample.bsdfSample.pdf)
                || result.sample.bsdfSample.eta <= 0.0
                || !primeTestClose(
                        length(result.sample.bsdfSample.wo), 1.0, 3.0e-5, 3.0e-5)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        bool reflected = wi.z * result.sample.bsdfSample.wo.z > 0.0;
        if (reflected != primeOpenPbrIsReflective(flags)
                || reflected == primeOpenPbrIsTransmissive(flags)) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
    }
    if (result.sample.volumeStack.count != 0u
            || !primeTestClose(result.sample.rayT, rayT, 0.0, 0.0)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    return result;
}

void primeCompactFoliageWrite(uint caseIndex, PrimeCompactFoliageTestResult result) {
    uint outputOffset = primeTestOutputOffset(caseIndex, primeTestOutputWords());
    primeTestOutput[outputOffset] = uint4(result.mask, result.kind, caseIndex, 0u);
    if (!primeTestWitnessEnabled()) { return; }
    primeTestOutput[outputOffset + 1u] = asuint(float4(
            result.evaluation.throughput.value, result.evaluation.pdf));
    primeTestOutput[outputOffset + 2u] = asuint(float4(
            result.components.diffuse, result.components.pdf));
    primeTestOutput[outputOffset + 3u] = asuint(float4(result.components.specular, 0.0));
    primeTestOutput[outputOffset + 4u] = asuint(float4(result.energy.diffuse, 0.0));
    primeTestOutput[outputOffset + 5u] = asuint(float4(result.energy.specular, 0.0));
    primeTestOutput[outputOffset + 6u] = asuint(float4(
            result.sample.bsdfSample.throughput.value, result.sample.bsdfSample.pdf));
    primeTestOutput[outputOffset + 7u] = asuint(float4(
            result.sample.bsdfSample.wo, result.sample.bsdfSample.eta));
    primeTestOutput[outputOffset + 8u] = asuint(float4(
            result.sampledEvaluation.throughput.value,
            result.sampledEvaluation.pdf));
    primeTestOutput[outputOffset + 9u] = uint4(
            result.evaluation.throughput.flags,
            result.sample.bsdfSample.throughput.flags,
            result.sampledEvaluation.throughput.flags,
            result.sample.volumeStack.count);
    primeTestOutput[outputOffset + 10u] = asuint(float4(result.sample.rayT, 0.0, 0.0, 0.0));
    primeTestOutput[outputOffset + 11u] = uint4(0u);
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
