#include "core/prime_test_abi.slang"
import "bsdf/compact/openpbr_common.slang";
import "bsdf/compact/openpbr_material.slang";
import "bsdf/lite/bsdf.slang";

static const uint PRIME_LITE_INPUT_WORDS = 4u;

struct PrimeLiteTestResult {
    uint mask;
    uint kind;
    float3 evaluation;
    float evaluationPdf;
    float3 diffuse;
    float3 specular;
    float3 energy;
    PrimeOpenPbrSampleResult sample;
    float3 sampledEvaluation;
    float sampledEvaluationPdf;
};

float primeLiteTestParam(uint4 encoded[4], uint index) {
    uint flatIndex = index + 1u;
    return asfloat(encoded[flatIndex / 4u][flatIndex % 4u]);
}

float3 primeLiteTestDirection(float cosineTheta, float phi) {
    float radius = sqrt(max(0.0, 1.0 - cosineTheta * cosineTheta));
    return float3(radius * cos(phi), radius * sin(phi), cosineTheta);
}

PrimeOpenPbrVolumeStack primeLiteTestStack(
        bool inside, PrimeOpenPbrMaterial material) {
    PrimeOpenPbrVolumeStack stack;
    PrimeOpenPbrVolume empty;
    empty.extinction = float3(0.0);
    empty.albedo = float3(0.0);
    empty.anisotropy = 0.0;
    empty.ior = 1.0;
    stack.values[0] = empty;
    stack.values[1] = empty;
    stack.count = 0u;
    if (inside) {
        stack.values[0] = primeOpenPbrVolumeFromTransmission(material.transmission);
        stack.values[0].ior = material.specular.ior;
        stack.count = 1u;
    }
    return stack;
}

void primeLiteTestClassify(inout uint mask, float value) {
    mask |= primeTestClassify(value);
}

void primeLiteTestClassify(inout uint mask, float3 value) {
    mask |= primeTestClassify(value);
}

bool primeLiteTestNonnegative(float value) {
    return value >= 0.0 && primeTestClassify(value) == 0u;
}

bool primeLiteTestNonnegative(float3 value) {
    return all(value >= float3(0.0)) && primeTestClassify(value) == 0u;
}

bool primeLiteTestUnit(float3 value) {
    return primeTestClose(length(value), 1.0, 3.0e-5, 3.0e-5);
}

void primeLiteTestObserve(
        inout PrimeLiteTestResult result,
        PrimeOpenPbrThroughput throughput,
        float pdf) {
    primeLiteTestClassify(result.mask, throughput.value);
    primeLiteTestClassify(result.mask, pdf);
    if (!primeLiteTestNonnegative(throughput.value)
            || !primeLiteTestNonnegative(pdf)) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
}

PrimeLiteTestResult primeLiteTestRun(uint4 encoded[4]) {
    PrimeLiteTestResult result;
    result.mask = 0u;
    result.kind = encoded[0].x;
    result.evaluation = float3(0.0);
    result.evaluationPdf = 0.0;
    result.diffuse = float3(0.0);
    result.specular = float3(0.0);
    result.energy = float3(0.0);
    result.sample = primeLiteZeroSampleResult(0.0, primeLiteTestStack(
            false, primeCompactMaterialFromMetallic(
                    float3(1.0), 0.5, 0.0, float3(0.0, 0.0, 1.0))));
    result.sampledEvaluation = float3(0.0);
    result.sampledEvaluationPdf = 0.0;

    float roughness = primeLiteTestParam(encoded, 0u);
    float ior = primeLiteTestParam(encoded, 1u);
    float3 wi = primeLiteTestDirection(
            primeLiteTestParam(encoded, 2u), primeLiteTestParam(encoded, 3u));
    float3 wo = primeLiteTestDirection(
            primeLiteTestParam(encoded, 4u), primeLiteTestParam(encoded, 5u));
    float3 randomValue = float3(
            primeLiteTestParam(encoded, 6u),
            primeLiteTestParam(encoded, 7u),
            primeLiteTestParam(encoded, 8u));
    float3 color = float3(
            primeLiteTestParam(encoded, 9u),
            primeLiteTestParam(encoded, 10u),
            primeLiteTestParam(encoded, 11u));
    float inverseOutsideIor = primeLiteTestParam(encoded, 12u);
    float rayT = primeLiteTestParam(encoded, 13u);
    bool alternate = primeLiteTestParam(encoded, 14u) >= 0.5;

    float normalF0 = primeOpenPbrIorToF0(ior);
    float normalFresnel = primeLiteDielectricFresnel(ior, 1.0);
    float grazingFresnel = primeLiteDielectricFresnel(ior, 0.0);
    primeLiteTestClassify(result.mask, normalFresnel);
    primeLiteTestClassify(result.mask, grazingFresnel);
    if (!primeTestClose(normalFresnel, normalF0, 2.0e-6, 2.0e-5)
            || normalFresnel < 0.0 || normalFresnel > 1.0
            || grazingFresnel < 0.0 || grazingFresnel > 1.0) {
        result.mask |= PRIME_TEST_FAILURE_VALUE;
    }

    if (result.kind <= 2u) {
        bool conductor = result.kind == 1u;
        PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
                color, roughness, conductor ? 1.0 : 0.0, float3(0.0, 0.0, 1.0));
        material.specular.ior = ior;
        if (result.kind == 2u) {
            material.weight.subsurface = 0.5;
            material.subsurface.color = color;
            material.subsurface.scatterAnisotropy = alternate ? 0.6 : -0.35;
            material.geometry.thinWalled = alternate ? 1u : 0u;
        }
        if (!primeLiteOpaqueSupports(material)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        PrimeOpenPbrMaterial unsupported = material;
        unsupported.weight.coat = 0.25;
        if (primeLiteOpaqueSupports(unsupported)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        PrimeLiteOpaqueState state = primeLiteOpaqueStateInit(
                primeLiteEnergy(), material, wi, inverseOutsideIor, rayT);
        PrimeLiteOpaqueEvaluation evaluated = primeLiteOpaqueEvaluate(wi, wo, state);
        PrimeLiteOpaqueComponents components = primeLiteOpaqueEvaluateComponents(
                wi, wo, state);
        PrimeLiteOpaqueEnergy energy = primeLiteOpaqueEnergy(wi, state);
        result.evaluation = evaluated.throughput.value;
        result.evaluationPdf = evaluated.pdf;
        result.diffuse = components.diffuse;
        result.specular = components.specular;
        result.energy = energy.diffuse + energy.specular;
        PrimeOpenPbrVolumeStack stack = primeLiteTestStack(false, material);
        result.sample = primeLiteOpaqueSample(wi, randomValue, state, stack);
        if (result.kind != 1u
                && (!(state.layer.transmittanceIn >= 0.0)
                        || !(state.layer.transmittanceIn <= 1.0)
                        || !(state.layer.specularScale >= 1.0)
                        || primeTestClassify(state.layer.transmittanceIn) != 0u
                        || primeTestClassify(state.layer.specularScale) != 0u)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        if (!primeTestClose(
                    result.evaluation,
                    result.diffuse + result.specular,
                    5.0e-6,
                    3.0e-5)
                || !primeTestClose(
                    result.evaluationPdf, components.pdf, 5.0e-6, 3.0e-5)) {
            result.mask |= PRIME_TEST_FAILURE_VALUE;
        }
        if (result.kind == 1u && wi.z > 0.0 && wo.z > 0.0
                && !primeLiteMicrofacetEffectivelySmooth(state.microfacet)) {
            PrimeLiteOpaqueState reverseState = primeLiteOpaqueStateInit(
                    primeLiteEnergy(), material, wo, inverseOutsideIor, rayT);
            PrimeLiteOpaqueEvaluation reverse = primeLiteOpaqueEvaluate(
                    wo, wi, reverseState);
            float3 forwardBsdf = result.evaluation / max(wo.z, 1.0e-6);
            float3 reverseBsdf = reverse.throughput.value / max(wi.z, 1.0e-6);
            if (!primeTestClose(forwardBsdf, reverseBsdf, 2.0e-4, 1.5e-3)) {
                result.mask |= PRIME_TEST_FAILURE_RECIPROCITY;
            }
        }
        if (result.kind == 0u && wi.z > 0.0) {
            float3 normalLight = float3(0.0, 0.0, 1.0);
            float3 grazingLight = primeLiteTestDirection(0.01, 0.7);
            PrimeLiteOpaqueComponents normalComponents = primeLiteOpaqueEvaluateComponents(
                    wi, normalLight, state);
            PrimeLiteOpaqueComponents grazingComponents = primeLiteOpaqueEvaluateComponents(
                    wi, grazingLight, state);
            float3 normalDiffuseBrdf = normalComponents.diffuse;
            float3 grazingDiffuseBrdf = grazingComponents.diffuse / grazingLight.z;
            if (!primeTestClose(normalDiffuseBrdf, grazingDiffuseBrdf, 2.0e-5, 2.0e-4)) {
                result.mask |= PRIME_TEST_FAILURE_VALUE;
            }

            PrimeOpenPbrMaterial grazingMaterial = primeCompactMaterialFromMetallic(
                    float3(1.0), 0.5, 0.0, float3(0.0, 0.0, 1.0));
            grazingMaterial.specular.ior = 1.5;
            PrimeLiteOpaqueState normalState = primeLiteOpaqueStateInit(
                    primeLiteEnergy(), grazingMaterial, normalLight, 1.0, rayT);
            PrimeLiteOpaqueState grazingState = primeLiteOpaqueStateInit(
                    primeLiteEnergy(), grazingMaterial, grazingLight, 1.0, rayT);
            float3 normalViewDiffuse = primeLiteOpaqueEvaluateComponents(
                    normalLight, normalLight, normalState).diffuse;
            float3 grazingViewDiffuse = primeLiteOpaqueEvaluateComponents(
                    grazingLight, normalLight, grazingState).diffuse;
            if (any(grazingViewDiffuse < 0.5 * normalViewDiffuse)) {
                result.mask |= PRIME_TEST_FAILURE_VALUE;
            }
        }
        if (result.sample.bsdfSample.throughput.flags != PRIME_OPENPBR_FLAG_NONE
                && !primeOpenPbrIsDelta(result.sample.bsdfSample.throughput.flags)) {
            PrimeLiteOpaqueEvaluation sampled = primeLiteOpaqueEvaluate(
                    wi, result.sample.bsdfSample.wo, state);
            result.sampledEvaluation = sampled.throughput.value;
            result.sampledEvaluationPdf = sampled.pdf;
        }
    } else if (result.kind == 3u) {
        PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
                color, roughness, 0.0, float3(0.0, 0.0, 1.0));
        material.specular.ior = 1.45;
        material.geometry.thinWalled = 1u;
        material.weight.transmission = 0.15;
        material.transmission.color = color;
        material.transmission.depth = 0.0;
        if (!primeLiteFoliageSupports(material)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        PrimeLiteFoliageState state = primeLiteFoliageStateInit(
                primeLiteEnergy(), material, wi, inverseOutsideIor, rayT);
        PrimeLiteFoliageEvaluation evaluated = primeLiteFoliageEvaluate(wi, wo, state);
        PrimeLiteFoliageComponents components = primeLiteFoliageEvaluateComponents(
                wi, wo, state);
        PrimeLiteFoliageEnergy energy = primeLiteFoliageEnergy(
                primeLiteEnergy(), wi, state);
        result.evaluation = evaluated.throughput.value;
        result.evaluationPdf = evaluated.pdf;
        result.diffuse = components.diffuse;
        result.specular = components.specular;
        result.energy = energy.diffuse + energy.specular;
        result.sample = primeLiteFoliageSample(
                wi, randomValue, state, primeLiteTestStack(false, material));
        if (!primeTestClose(
                    result.evaluation,
                    result.diffuse + result.specular,
                    5.0e-6,
                    3.0e-5)
                || !primeTestClose(
                    result.evaluationPdf, components.pdf, 5.0e-6, 3.0e-5)) {
            result.mask |= PRIME_TEST_FAILURE_VALUE;
        }
        if (result.sample.bsdfSample.throughput.flags != PRIME_OPENPBR_FLAG_NONE
                && !primeOpenPbrIsDelta(result.sample.bsdfSample.throughput.flags)) {
            PrimeLiteFoliageEvaluation sampled = primeLiteFoliageEvaluate(
                    wi, result.sample.bsdfSample.wo, state);
            result.sampledEvaluation = sampled.throughput.value;
            result.sampledEvaluationPdf = sampled.pdf;
        }
    } else {
        bool thinWalled = result.kind == 5u;
        PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
                float3(1.0), roughness, 0.0, float3(0.0, 0.0, 1.0));
        material.weight.transmission = 1.0;
        material.specular.ior = ior;
        material.geometry.thinWalled = thinWalled ? 1u : 0u;
        material.transmission.color = color;
        material.transmission.depth = thinWalled ? 0.0 : 1.0;
        if (!primeLiteTransmissionSupports(material)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        PrimeOpenPbrVolumeStack stack = primeLiteTestStack(wi.z < 0.0, material);
        uint originalStackCount = stack.count;
        PrimeLiteTransmissionState state = primeLiteTransmissionStateInit(
                primeLiteEnergy(), material, wi, inverseOutsideIor, rayT);
        state = primeLiteTransmissionInterfaceState(
                primeLiteEnergy(), wi, state);
        PrimeLiteTransmissionEvaluation evaluated = primeLiteTransmissionEvaluate(
                wi, wo, state, PRIME_OPENPBR_FLAG_ALL);
        result.evaluation = evaluated.throughput.value;
        result.evaluationPdf = evaluated.pdf;
        result.specular = evaluated.throughput.value;
        result.energy = primeLiteTransmissionEnergy(
                primeLiteEnergy(), wi, state, PRIME_OPENPBR_FLAG_ALL);
        result.sample = primeLiteTransmissionSample(
                wi, randomValue, state, PRIME_OPENPBR_FLAG_ALL, stack);
        uint sampleFlags = result.sample.bsdfSample.throughput.flags;
        if (sampleFlags != PRIME_OPENPBR_FLAG_NONE
                && !primeOpenPbrIsDelta(sampleFlags)) {
            PrimeLiteTransmissionEvaluation sampled = primeLiteTransmissionEvaluate(
                    wi, result.sample.bsdfSample.wo, state, PRIME_OPENPBR_FLAG_ALL);
            result.sampledEvaluation = sampled.throughput.value;
            result.sampledEvaluationPdf = sampled.pdf;
        }
        if (sampleFlags != PRIME_OPENPBR_FLAG_NONE) {
            uint expectedStackCount = originalStackCount;
            if (!thinWalled && primeOpenPbrIsTransmissive(sampleFlags)) {
                expectedStackCount = wi.z >= 0.0 ? 1u : 0u;
            }
            if (result.sample.volumeStack.count != expectedStackCount) {
                result.mask |= PRIME_TEST_FAILURE_STATE;
            }
        }
    }

    PrimeOpenPbrThroughput evaluationThroughput;
    evaluationThroughput.value = result.evaluation;
    evaluationThroughput.flags = all(result.evaluation == float3(0.0))
            ? PRIME_OPENPBR_FLAG_NONE : PRIME_OPENPBR_FLAG_SPECULAR;
    primeLiteTestObserve(result, evaluationThroughput, result.evaluationPdf);
    primeLiteTestClassify(result.mask, result.diffuse);
    primeLiteTestClassify(result.mask, result.specular);
    primeLiteTestClassify(result.mask, result.energy);
    if (!primeLiteTestNonnegative(result.diffuse)
            || !primeLiteTestNonnegative(result.specular)
            || !primeLiteTestNonnegative(result.energy)
            || any(result.energy > float3(1.0005))) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }

    uint flags = result.sample.bsdfSample.throughput.flags;
    if (flags != PRIME_OPENPBR_FLAG_NONE) {
        primeLiteTestClassify(result.mask, result.sample.bsdfSample.throughput.value);
        primeLiteTestClassify(result.mask, result.sample.bsdfSample.pdf);
        primeLiteTestClassify(result.mask, result.sample.bsdfSample.eta);
        primeLiteTestClassify(result.mask, result.sample.bsdfSample.wo);
        if (!primeLiteTestNonnegative(result.sample.bsdfSample.throughput.value)
                || !(result.sample.bsdfSample.pdf > 0.0)
                || !(result.sample.bsdfSample.eta > 0.0)
                || !primeLiteTestUnit(result.sample.bsdfSample.wo)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        bool reflected = wi.z * result.sample.bsdfSample.wo.z > 0.0;
        if (reflected != primeOpenPbrIsReflective(flags)
                || reflected == primeOpenPbrIsTransmissive(flags)) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        if (!primeOpenPbrIsDelta(flags)) {
            primeLiteTestClassify(result.mask, result.sampledEvaluation);
            primeLiteTestClassify(result.mask, result.sampledEvaluationPdf);
            if (!primeLiteTestNonnegative(result.sampledEvaluation)
                    || !(result.sampledEvaluationPdf > 0.0)
                    || all(result.sampledEvaluation == float3(0.0))) {
                result.mask |= PRIME_TEST_FAILURE_PDF;
            }
        }
    }
    return result;
}

void primeLiteTestWrite(uint caseIndex, PrimeLiteTestResult result) {
    uint outputOffset = primeTestOutputOffset(caseIndex, primeTestOutputWords());
    primeTestOutput[outputOffset] = uint4(result.mask, result.kind, caseIndex, 0u);
    if (!primeTestWitnessEnabled()) { return; }
    primeTestOutput[outputOffset + 1u] = asuint(float4(
            result.evaluation, result.evaluationPdf));
    primeTestOutput[outputOffset + 2u] = asuint(float4(result.diffuse, 0.0));
    primeTestOutput[outputOffset + 3u] = asuint(float4(result.specular, 0.0));
    primeTestOutput[outputOffset + 4u] = asuint(float4(result.energy, 0.0));
    primeTestOutput[outputOffset + 5u] = asuint(float4(
            result.sample.bsdfSample.throughput.value,
            result.sample.bsdfSample.pdf));
    primeTestOutput[outputOffset + 6u] = asuint(float4(
            result.sample.bsdfSample.wo,
            result.sample.bsdfSample.eta));
    primeTestOutput[outputOffset + 7u] = uint4(
            result.sample.bsdfSample.throughput.flags,
            result.sample.volumeStack.count,
            asuint(result.sample.rayT),
            0u);
    primeTestOutput[outputOffset + 8u] = asuint(float4(
            result.sampledEvaluation, result.sampledEvaluationPdf));
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) { return; }
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_LITE_INPUT_WORDS);
    uint4 encoded[4];
    for (uint word = 0u; word < PRIME_LITE_INPUT_WORDS; word++) {
        encoded[word] = primeTestInput[inputOffset + word];
    }
    primeLiteTestWrite(caseIndex, primeLiteTestRun(encoded));
}
