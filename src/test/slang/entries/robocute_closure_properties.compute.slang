
#include "core/prime_test_abi.slang"
import "bsdf/adapter/prime_bsdf_specializations.slang";
import "bsdf/adapter/transmission_ggx_energy.slang";
import "bsdf/core/robocute_closures.slang";
import "bsdf/core/robocute_common.slang";
import "bsdf/core/robocute_fresnel.slang";
import "bsdf/core/robocute_microfacet.slang";
import "bsdf/core/robocute_openpbr.slang";
bool2 greaterThanEqual(float2 first, float2 second) { return first >= second; }
bool3 greaterThanEqual(float3 first, float3 second) { return first >= second; }
bool2 lessThanEqual(float2 first, float2 second) { return first <= second; }
bool3 lessThanEqual(float3 first, float3 second) { return first <= second; }
bool2 lessThan(float2 first, float2 second) { return first < second; }
bool3 lessThan(float3 first, float3 second) { return first < second; }

static const uint PRIME_CLOSURE_INPUT_WORDS = 6u;
static const uint PRIME_CLOSURE_WITNESS_WORDS = 12u;

static const uint PRIME_CLOSURE_BASIC_METALLIC = 0u;
static const uint PRIME_CLOSURE_SUBSURFACE_GLOSSY = 1u;
static const uint PRIME_CLOSURE_TRANSMISSION = 2u;
static const uint PRIME_CLOSURE_THIN_WALL = 3u;

struct PrimeClosureResult {
    uint mask;
    uint4 witness[5];
};

void primeClosureClear(out PrimeClosureResult result) {
    result.mask = 0u;
    for (uint index = 0u; index < 5u; index++) {
        result.witness[index] = uint4(0u);
    }
}

float primeClosureParam(uint4 encoded[6], uint index) {
    uint flatIndex = index + 1u;
    return asfloat(encoded[flatIndex / 4u][flatIndex % 4u]);
}

void primeClosureClassify(inout uint mask, float value) {
    mask |= primeTestClassify(value);
}

void primeClosureClassify(inout uint mask, float2 value) {
    mask |= primeTestClassify(value);
}

void primeClosureClassify(inout uint mask, float3 value) {
    mask |= primeTestClassify(value);
}

bool primeClosureUnitRange(float value) {
    return value >= -1.0e-6 && value <= 1.0 + 1.0e-6;
}

bool primeClosureUnitRange(float3 value) {
    return all(greaterThanEqual(value, float3(-1.0e-6)))
            && all(lessThanEqual(value, float3(1.0 + 1.0e-6)));
}

void primeClosureClassifyMix(inout uint mask, PrimeRcMixState state) {
    primeClosureClassify(mask, state.firstSampleWeight);
    primeClosureClassify(mask, state.secondSampleWeight);
    float sum = state.firstSampleWeight + state.secondSampleWeight;
    if (!primeClosureUnitRange(state.firstSampleWeight)
            || !primeClosureUnitRange(state.secondSampleWeight)
            || !(primeTestClose(sum, 0.0, 1.0e-6, 1.0e-6)
            || primeTestClose(sum, 1.0, 1.0e-6, 1.0e-6))) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }
}

void primeClosureClassifyLayer(inout uint mask, PrimeRcLayerState state) {
    primeClosureClassify(mask, state.coatTransIn);
    primeClosureClassify(mask, state.subSampleWeight);
    primeClosureClassify(mask, state.coatSampleWeight);
    float sum = state.subSampleWeight + state.coatSampleWeight;
    if (!primeClosureUnitRange(state.subSampleWeight)
            || !primeClosureUnitRange(state.coatSampleWeight)
            || !(primeTestClose(sum, 0.0, 1.0e-6, 1.0e-6)
            || primeTestClose(sum, 1.0, 1.0e-6, 1.0e-6))) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }
}

void primeClosureClassifyState(inout uint mask, PrimeRcState state) {
    primeClosureClassify(mask, state.specularMicrofacet.alpha);
    primeClosureClassify(mask, state.specularFresnel.ior);
    primeClosureClassify(mask, state.specularFresnel.energyIor);
    primeClosureClassify(mask, state.specularFresnel.color);
    primeClosureClassify(mask, state.specularMultipleScattering);
    primeClosureClassify(mask, state.conductorFresnel.f0);
    primeClosureClassify(mask, state.conductorFresnel.f82);
    primeClosureClassify(mask, state.conductorFresnel.f90);
    primeClosureClassify(mask, state.conductorFresnel.energyF0);
    primeClosureClassify(mask, state.conductorEss);
    primeClosureClassify(mask, state.transmissionMicrofacet.alpha);
    primeClosureClassify(mask, state.transmissionTint);
    primeClosureClassify(mask, state.transmissionVolume.extinction);
    primeClosureClassify(mask, state.transmissionVolume.albedo);
    primeClosureClassify(mask, state.transmissionVolume.anisotropy);
    primeClosureClassify(mask, state.transmissionVolume.ior);
    primeClosureClassify(mask, state.transmissionMultipleScattering);
    primeClosureClassifyMix(mask, state.basicMetal);
    primeClosureClassifyLayer(mask, state.basicGlossy);
    primeClosureClassifyLayer(mask, state.subsurfaceGlossy);
    primeClosureClassifyMix(mask, state.mixedDiffuse);
    primeClosureClassifyLayer(mask, state.glossyDiffuse);
    primeClosureClassifyMix(mask, state.dielectricBase);
    primeClosureClassifyMix(mask, state.baseSubstrate);
    if (any(lessThan(state.specularMicrofacet.alpha, float2(0.0)))
            || any(lessThan(state.transmissionMicrofacet.alpha, float2(0.0)))
            || any(lessThan(state.transmissionVolume.extinction, float3(0.0)))
            || !primeClosureUnitRange(state.transmissionVolume.albedo)
            || state.transmissionVolume.ior <= 0.0) {
        mask |= PRIME_TEST_FAILURE_RANGE;
    }
}

PrimeRcVolumeStack primeClosureStack(uint count, float ior) {
    PrimeRcVolumeStack stack;
    PrimeRcVolume volume;
    volume.extinction = float3(0.01, 0.02, 0.03);
    volume.albedo = float3(0.0);
    volume.anisotropy = 0.0;
    volume.ior = ior;
    stack.values[0] = volume;
    volume.ior = max(1.0, ior + 0.1);
    stack.values[1] = volume;
    stack.count = min(count, PRIME_RC_MAX_VOLUME_STACK_SIZE);
    return stack;
}

uint primeClosureExpectedStackCount(
        uint kind,
        PrimeRcState state,
        PrimeRcSample sampleValue,
        uint originalCount) {
    if (sampleValue.throughput.flags == PRIME_RC_FLAG_NONE
            || state.geometryThinWalled != 0u
            || kind == PRIME_CLOSURE_BASIC_METALLIC
            || kind == PRIME_CLOSURE_SUBSURFACE_GLOSSY
            || kind == PRIME_CLOSURE_THIN_WALL) {
        return originalCount;
    }
    bool transmitted = primeRcIsTransmissive(sampleValue.throughput.flags);
    if (kind == PRIME_CLOSURE_TRANSMISSION) {
        if (transmitted) {
            if (state.entering != 0u) {
                return min(originalCount + 1u, PRIME_RC_MAX_VOLUME_STACK_SIZE);
            }
            return originalCount == 0u ? 0u : originalCount - 1u;
        }
    }
    return originalCount;
}

bool primeClosureStableFresnelReconstruction(
        float3 wi, float3 wo, float ior) {
    bool reflected = wi.z * wo.z > 0.0;
    float etaPath = reflected ? 1.0 : (wi.z > 0.0 ? ior : 1.0 / ior);
    float3 halfVector = wi + etaPath * wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (lengthSquared <= 1.0e-6) {
        return false;
    }
    halfVector *= rsqrt(lengthSquared);
    if (halfVector.z < 0.0) {
        halfVector = -halfVector;
    }
    float microIncident = dot(wi, halfVector);
    float microOutgoing = dot(wo, halfVector);
    if (microIncident * wi.z < 0.0 || microOutgoing * wo.z < 0.0) {
        return false;
    }
    float fresnelEta = microIncident < 0.0 ? 1.0 / ior : ior;
    if (fresnelEta >= 1.0) {
        return true;
    }
    float criticalCosine = sqrt(max(0.0, 1.0 - fresnelEta * fresnelEta));
    return abs(abs(microIncident) - criticalCosine) >= 1.0e-1;
}

void primeClosureProperties(
        uint kind, uint4 encoded[6], out PrimeClosureResult result) {
    primeClosureClear(result);
    float roughness = primeClosureParam(encoded, 0u);
    float weight = primeClosureParam(encoded, 1u);
    float3 wi = normalize(float3(
            primeClosureParam(encoded, 2u),
            primeClosureParam(encoded, 3u),
            primeClosureParam(encoded, 4u)));
    float3 randomValue = float3(
            primeClosureParam(encoded, 5u),
            primeClosureParam(encoded, 6u),
            primeClosureParam(encoded, 7u));
    float3 baseColor = float3(
            primeClosureParam(encoded, 8u),
            primeClosureParam(encoded, 9u),
            primeClosureParam(encoded, 10u));
    float ior = primeClosureParam(encoded, 11u);
    uint thinWalled = primeClosureParam(encoded, 12u) >= 0.5 ? 1u : 0u;
    float rayT = primeClosureParam(encoded, 13u);
    uint stackCount = uint(clamp(
            round(primeClosureParam(encoded, 14u)), 0.0, 2.0));
    float transmissionDepth = primeClosureParam(encoded, 15u);
    float3 transmissionColor = float3(
            primeClosureParam(encoded, 16u),
            primeClosureParam(encoded, 17u),
            primeClosureParam(encoded, 18u));
    float diffuseRoughness = primeClosureParam(encoded, 19u);
    float specularWeight = primeClosureParam(encoded, 20u);

    PrimeRcMaterial material = primeRcMaterialFromMetallic(
            baseColor,
            roughness,
            kind == PRIME_CLOSURE_BASIC_METALLIC ? weight : 0.0,
            float3(0.0, 0.0, 1.0));
    material.weight.specular = specularWeight;
    material.weight.diffuseRoughness = diffuseRoughness;
    material.specular.ior = ior;
    material.geometry.thinWalled =
            kind == PRIME_CLOSURE_THIN_WALL ? 1u : thinWalled;
    material.geometry.thickness = material.geometry.thinWalled != 0u
            ? 0.0625 : 1.0;
    material.transmission.color = transmissionColor;
    material.transmission.depth = transmissionDepth;
    material.transmission.scatter = float3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.subsurface.color = baseColor;
    material.subsurface.radius = float3(0.05, 0.025, 0.0125);
    material.subsurface.scatterAnisotropy = 0.0;
    if (kind == PRIME_CLOSURE_SUBSURFACE_GLOSSY) {
        material.weight.subsurface = weight;
    } else if (kind == PRIME_CLOSURE_TRANSMISSION) {
        material.weight.transmission = 1.0;
    } else if (kind == PRIME_CLOSURE_THIN_WALL) {
        material.weight.transmission = weight;
        material.weight.subsurface = 0.5 * (1.0 - weight);
    }

    PrimeRcVolumeStack stack = primeClosureStack(stackCount, ior);
    float inverseOutsideIor = primeRcInverseOutsideIor(wi.z, stack);
    float3 wavelengths = float3(630.0, 532.0, 467.0);
    PrimeRcState state;
    if (kind == PRIME_CLOSURE_BASIC_METALLIC) {
        state = primeRcBasicMetallicStateInit(
                primeTransmissionGgxEnergy(),
                material, wi, inverseOutsideIor, rayT, wavelengths,
                0u, PRIME_RC_DETAIL_DEFAULT, 0u);
    } else if (kind == PRIME_CLOSURE_SUBSURFACE_GLOSSY) {
        state = primeRcSubsurfaceGlossyStateInit(
                primeTransmissionGgxEnergy(),
                material, wi, inverseOutsideIor, rayT, wavelengths,
                0u, PRIME_RC_DETAIL_DEFAULT, 0u);
    } else if (kind == PRIME_CLOSURE_TRANSMISSION) {
        state = primeRcTransmissionStateInit(
                primeTransmissionGgxEnergy(),
                material, wi, inverseOutsideIor, rayT, wavelengths,
                0u, PRIME_RC_DETAIL_DEFAULT, 0u);
        state = primeRcPrimeTransmissionInterfaceState(wi, state);
    } else {
        state = primeRcPrimeThinWallStateInit(
                material, wi, inverseOutsideIor, rayT, wavelengths,
                0u, PRIME_RC_DETAIL_DEFAULT, 0u);
    }
    state.randomValue = randomValue;
    primeClosureClassifyState(result.mask, state);

    PrimeRcSampleResult sampled;
    if (kind == PRIME_CLOSURE_BASIC_METALLIC) {
        sampled = primeRcBasicMetallicSample(wi, randomValue, state, stack);
    } else if (kind == PRIME_CLOSURE_SUBSURFACE_GLOSSY) {
        sampled = primeRcSubsurfaceGlossySample(wi, randomValue, state, stack);
    } else if (kind == PRIME_CLOSURE_TRANSMISSION) {
        sampled = primeRcPrimeTransmissionSample(wi, randomValue, state, stack);
    } else {
        sampled = primeRcPrimeThinWallSample(wi, randomValue, state, stack);
    }

    uint flags = sampled.bsdfSample.throughput.flags;
    float2 activeTransmissionAlpha = state.geometryThinWalled != 0u
            ? state.transmissionMicrofacet.alpha
            : state.specularMicrofacet.alpha;
    PrimeRcEval evaluated;
    evaluated.throughput.value = float3(0.0);
    evaluated.throughput.flags = PRIME_RC_FLAG_NONE;
    evaluated.pdf = 0.0;
    if (flags != PRIME_RC_FLAG_NONE) {
        if (kind == PRIME_CLOSURE_BASIC_METALLIC) {
            evaluated = primeRcPrimeBasicMetallicEvaluate(
                    wi, sampled.bsdfSample.wo, state);
        } else if (kind == PRIME_CLOSURE_SUBSURFACE_GLOSSY) {
            evaluated = primeRcPrimeSubsurfaceGlossyEvaluate(
                    wi, sampled.bsdfSample.wo, state);
        } else if (kind == PRIME_CLOSURE_TRANSMISSION) {
            evaluated = primeRcPrimeTransmissionEvaluate(
                    wi, sampled.bsdfSample.wo, state);
        } else {
            evaluated = primeRcPrimeThinWallEvaluate(wi, sampled.bsdfSample.wo, state);
        }
    }

    float3 probeWo = primeRcCosineSampleHemisphere(randomValue.xy);
    bool supportsTransmissionProbe = kind == PRIME_CLOSURE_TRANSMISSION
            || kind == PRIME_CLOSURE_THIN_WALL
            || (kind == PRIME_CLOSURE_SUBSURFACE_GLOSSY
            && state.geometryThinWalled != 0u);
    if (supportsTransmissionProbe && randomValue.z < 0.5) {
        probeWo.z = -probeWo.z;
    }
    PrimeRcEval probe;
    if (kind == PRIME_CLOSURE_BASIC_METALLIC) {
        probe = primeRcPrimeBasicMetallicEvaluate(wi, probeWo, state);
    } else if (kind == PRIME_CLOSURE_SUBSURFACE_GLOSSY) {
        probe = primeRcPrimeSubsurfaceGlossyEvaluate(wi, probeWo, state);
    } else if (kind == PRIME_CLOSURE_TRANSMISSION) {
        probe = primeRcPrimeTransmissionEvaluate(wi, probeWo, state);
    } else {
        probe = primeRcPrimeThinWallEvaluate(wi, probeWo, state);
    }

    bool numericInterior =
            min(
                    state.specularMicrofacet.alpha.x,
                    state.specularMicrofacet.alpha.y) >= 5.0e-3
            && abs(state.specularFresnel.ior - 1.0) >= 1.0e-2
            && abs(wi.z) >= 1.0e-1
            && abs(probeWo.z) >= 1.0e-2
            && (flags == PRIME_RC_FLAG_NONE
            || abs(sampled.bsdfSample.wo.z) >= 1.0e-2);
    primeClosureClassify(result.mask, sampled.bsdfSample.wo);
    primeClosureClassify(result.mask, sampled.bsdfSample.eta);
    primeClosureClassify(result.mask, sampled.rayT);
    if (numericInterior) {
        if (flags != PRIME_RC_FLAG_NONE) {
            primeClosureClassify(result.mask, sampled.bsdfSample.pdf);
            primeClosureClassify(result.mask, sampled.bsdfSample.throughput.value);
        }
        primeClosureClassify(result.mask, evaluated.throughput.value);
        primeClosureClassify(result.mask, evaluated.pdf);
        primeClosureClassify(result.mask, probe.throughput.value);
        primeClosureClassify(result.mask, probe.pdf);
    }
    const float negativeTolerance = -1.0e-6;
    if (numericInterior
            && ((flags != PRIME_RC_FLAG_NONE
            && (sampled.bsdfSample.pdf < negativeTolerance
            || any(lessThan(
                    sampled.bsdfSample.throughput.value,
                    float3(negativeTolerance)))))
            || evaluated.pdf < negativeTolerance
            || any(lessThan(
                    evaluated.throughput.value,
                    float3(negativeTolerance)))
            || probe.pdf < negativeTolerance
            || any(lessThan(
                    probe.throughput.value,
                    float3(negativeTolerance))))) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }

    if (flags != PRIME_RC_FLAG_NONE) {
        if ((flags & PRIME_RC_FLAG_ALL) == 0u
                || (flags & (flags - 1u)) != 0u) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        if (!primeTestClose(
                length(sampled.bsdfSample.wo), 1.0, 6.0e-5, 6.0e-5)) {
            result.mask |= PRIME_TEST_FAILURE_UNIT_LENGTH;
        }
        bool reflected = primeRcIsReflective(flags);
        if ((reflected && wi.z * sampled.bsdfSample.wo.z <= 0.0)
                || (!reflected && wi.z * sampled.bsdfSample.wo.z >= 0.0)) {
            result.mask |= PRIME_TEST_FAILURE_HEMISPHERE;
        }
        bool thickTransmission = !reflected
                && kind == PRIME_CLOSURE_TRANSMISSION
                && state.geometryThinWalled == 0u;
        float expectedEta = thickTransmission
                ? (state.entering != 0u
                        ? state.specularFresnel.ior
                        : 1.0 / state.specularFresnel.ior)
                : 1.0;
        if (!primeTestClose(
                sampled.bsdfSample.eta, expectedEta, 1.0e-7, 2.0e-6)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        if (numericInterior
                && primeRcIsDelta(flags)
                && kind == PRIME_CLOSURE_TRANSMISSION) {
            if (evaluated.throughput.flags != PRIME_RC_FLAG_NONE
                    || evaluated.pdf != 0.0) {
                result.mask |= PRIME_TEST_FAILURE_EVENT;
            }
        } else if (numericInterior
                && kind == PRIME_CLOSURE_TRANSMISSION
                && min(activeTransmissionAlpha.x, activeTransmissionAlpha.y)
                        >= 5.0e-3
                && abs(wi.z) >= 0.5
                // Tiny h reconstruction shifts near the critical angle can
                // cross the Fresnel branch and are not a float identity.
                && primeClosureStableFresnelReconstruction(
                        wi,
                        sampled.bsdfSample.wo,
                        state.specularFresnel.ior)
                && abs(state.specularFresnel.ior - 1.0) >= 0.01) {
            if (evaluated.throughput.flags != flags) {
                result.mask |= PRIME_TEST_FAILURE_EVENT;
            }
            if (!primeTestClose(
                    sampled.bsdfSample.pdf,
                    evaluated.pdf,
                    5.0e-6,
                    2.0e-3)) {
                result.mask |= PRIME_TEST_FAILURE_PDF;
            }
            if (!primeTestClose(
                    sampled.bsdfSample.throughput.value,
                    evaluated.throughput.value,
                    5.0e-6,
                    2.0e-3)) {
                result.mask |= PRIME_TEST_FAILURE_VALUE;
            }
        }
    }

    uint expectedStackCount = primeClosureExpectedStackCount(
            kind, state, sampled.bsdfSample, stackCount);
    if (sampled.volumeStack.count != expectedStackCount
            || sampled.volumeStack.count > PRIME_RC_MAX_VOLUME_STACK_SIZE) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    bool exitingSolidTransmission = kind == PRIME_CLOSURE_TRANSMISSION
            && state.geometryThinWalled == 0u
            && primeRcIsTransmissive(flags)
            && wi.z < 0.0
            && stackCount > 0u;
    float expectedRayT = exitingSolidTransmission ? 0.0 : rayT;
    if (!primeTestClose(sampled.rayT, expectedRayT, 1.0e-7, 1.0e-6)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    result.witness[0] = asuint(float4(
            state.specularMicrofacet.alpha,
            state.specularMultipleScattering.xy));
    result.witness[1] = uint4(
            asuint(sampled.bsdfSample.wo.x),
            asuint(sampled.bsdfSample.wo.y),
            asuint(sampled.bsdfSample.wo.z),
            flags);
    result.witness[2] = asuint(float4(
            sampled.bsdfSample.pdf,
            sampled.bsdfSample.throughput.value.x,
            evaluated.pdf,
            evaluated.throughput.value.x));
    result.witness[3] = asuint(float4(
            probe.throughput.value, probe.pdf));
    result.witness[4] = uint4(
            stackCount,
            sampled.volumeStack.count,
            asuint(sampled.rayT),
            asuint(state.specularFresnel.ior));
}

void primeClosureCommit(
        uint caseIndex,
        uint kind,
        uint4 encoded[6],
        PrimeClosureResult result) {
    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(
            result.mask, kind, caseIndex, 0u);
    if (outputWords >= PRIME_CLOSURE_WITNESS_WORDS) {
        for (uint index = 0u; index < PRIME_CLOSURE_INPUT_WORDS; index++) {
            primeTestOutput[outputOffset + 1u + index] = encoded[index];
        }
        for (uint index = 0u; index < 5u; index++) {
            primeTestOutput[outputOffset + 7u + index] =
                    result.witness[index];
        }
    }
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) {
        return;
    }
    uint inputOffset = primeTestInputOffset(
            caseIndex, PRIME_CLOSURE_INPUT_WORDS);
    uint4 encoded[6];
    for (uint index = 0u; index < PRIME_CLOSURE_INPUT_WORDS; index++) {
        encoded[index] = primeTestInput[inputOffset + index];
    }
    uint kind = encoded[0].x;
    PrimeClosureResult result;
    if (kind <= PRIME_CLOSURE_THIN_WALL) {
        primeClosureProperties(kind, encoded, result);
    } else {
        primeClosureClear(result);
        result.mask = PRIME_TEST_FAILURE_STATE;
    }
    primeClosureCommit(caseIndex, kind, encoded, result);
}
