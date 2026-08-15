#language slang 2026
module "bsdf/compact/openpbr_foliage.slang";

import "bsdf/compact/openpbr_opaque.slang";
import "bsdf/compact/openpbr_transmission.slang";
import "bsdf/core/robocute_common.slang";
import "bsdf/core/transmission_ggx_energy.slang";

// Exact compact specialization of OpenPBR's thin-wall base substrate used by
// Prime foliage. The inactive coat, fuzz, thin-film and diffraction graph is
// absent; conductor foliage bypasses the dielectric transmission mixture.

public struct PrimeCompactFoliageState {
    public PrimeCompactOpaqueState base;
    public PrimeRcMicrofacet transmissionMicrofacet;
    public float3 transmissionTint;
    public PrimeCompactMixState dielectricMix;
    public float transmissionWeight;
};

public struct PrimeCompactFoliageEvaluation {
    public PrimeRcThroughput throughput;
    public float pdf;
};

public struct PrimeCompactFoliageComponents {
    public float3 diffuse;
    public float3 specular;
    public float pdf;
};

public struct PrimeCompactFoliageEnergy {
    public float3 diffuse;
    public float3 specular;
};

PrimeCompactMixState primeCompactFoliageMakeMixState(
        float weight, float3 firstEnergy, float3 secondEnergy) {
    PrimeCompactMixState result;
    if (weight == 0.0) {
        result.firstSampleWeight = 1.0;
        result.secondSampleWeight = 0.0;
        return result;
    }
    if (weight == 1.0) {
        result.firstSampleWeight = 0.0;
        result.secondSampleWeight = 1.0;
        return result;
    }
    result.firstSampleWeight = (1.0 - weight) * primeRcSpectrumToWeight(firstEnergy);
    result.secondSampleWeight = weight * primeRcSpectrumToWeight(secondEnergy);
    float sum = result.firstSampleWeight + result.secondSampleWeight;
    if (sum > 0.0) {
        result.firstSampleWeight /= sum;
        result.secondSampleWeight /= sum;
    }
    return result;
}

PrimeCompactTransmissionState primeCompactFoliageTransmissionState(
        PrimeCompactFoliageState state) {
    PrimeCompactTransmissionState result;
    result.onb = state.base.onb;
    result.geometryThinWalled = 1u;
    result.entering = 1u;
    result.rayT = state.base.rayT;
    result.originalIor = state.base.dielectricFresnel.ior;
    result.transmissionColor = state.transmissionTint;
    result.specularMicrofacet = state.base.microfacet;
    result.specularFresnel = state.base.dielectricFresnel;
    result.transmissionMicrofacet = state.transmissionMicrofacet;
    result.transmissionTint = state.transmissionTint;
    result.transmissionVolume.extinction = float3(0.0);
    result.transmissionVolume.albedo = float3(0.0);
    result.transmissionVolume.anisotropy = 0.0;
    result.transmissionVolume.ior = 1.0;
    result.transmissionMultipleScattering = float3(1.0, 1.0, 0.0);
    return result;
}

PrimeRcThroughput primeCompactFoliageMixEval(
        PrimeRcThroughput first,
        PrimeRcThroughput second,
        float weight) {
    PrimeRcThroughput result = primeRcZeroThroughput();
    if (weight < 1.0) { result = first; }
    if (weight > 0.0) {
        result = primeRcThroughputScale(result, 1.0 - weight);
        result = primeRcThroughputAdd(
                result, primeRcThroughputScale(second, weight));
    }
    return result;
}

public bool primeCompactFoliageSupports(PrimeRcMaterial material) {
    return material.weight.base == 1.0
            && material.weight.specular == 1.0
            && (material.weight.metalness == 0.0 || material.weight.metalness == 1.0)
            && material.weight.subsurface >= 0.0
            && material.weight.subsurface <= 1.0
            && (material.weight.subsurface == 0.0 || material.weight.metalness == 0.0)
            && material.weight.transmission >= 0.0
            && material.weight.transmission <= 1.0
            && material.weight.coat == 0.0
            && material.weight.fuzz == 0.0
            && material.weight.thinFilm == 0.0
            && material.weight.diffraction == 0.0
            && material.weight.diffuseRoughness == 0.0
            && material.geometry.thinWalled != 0u
            && material.transmission.depth == 0.0
            && material.transmission.dispersionScale == 0.0;
}

public PrimeCompactFoliageState primeCompactFoliageStateInit<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        PrimeRcMaterial material,
        float3 wi,
        float inverseOutsideIor,
        float rayT)
    where TransmissionGgxEnergy : IPrimeRcTransmissionGgxEnergy
{
    PrimeCompactFoliageState state;
    state.base = primeCompactOpaqueStateInit(
            transmissionGgxEnergy, material, wi, inverseOutsideIor, rayT);
    state.transmissionWeight = material.weight.transmission;
    PrimeCompactTransmissionState transmission = primeCompactTransmissionStateInit(
            transmissionGgxEnergy, material, wi, inverseOutsideIor, rayT);
    state.transmissionMicrofacet = transmission.transmissionMicrofacet;
    state.transmissionTint = transmission.transmissionTint;

    PrimeCompactOpaqueEnergy baseEnergy = primeCompactOpaqueEnergy(wi, state.base);
    float3 glossyDiffuseEnergy = baseEnergy.specular + baseEnergy.diffuse;
    float3 transmissionEnergy = primeCompactTransmissionEnergy(
            transmissionGgxEnergy, wi, transmission, PRIME_RC_FLAG_ALL);
    state.dielectricMix = primeCompactFoliageMakeMixState(
            state.transmissionWeight,
            state.transmissionWeight < 1.0 ? glossyDiffuseEnergy : float3(0.0),
            state.transmissionWeight > 0.0 ? transmissionEnergy : float3(0.0));
    return state;
}

public PrimeCompactFoliageEvaluation primeCompactFoliageEvaluate(
        float3 wi, float3 wo, PrimeCompactFoliageState state) {
    PrimeCompactOpaqueEvaluation base = primeCompactOpaqueEvaluate(wi, wo, state.base);
    PrimeCompactFoliageEvaluation result;
    if (state.base.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR) {
        result.throughput = base.throughput;
        result.pdf = base.pdf;
        return result;
    }
    PrimeCompactTransmissionEvaluation transmission = primeCompactTransmissionEvaluate(
            wi,
            wo,
            primeCompactFoliageTransmissionState(state),
            PRIME_RC_FLAG_ALL);
    result.throughput = primeCompactFoliageMixEval(
            base.throughput, transmission.throughput, state.transmissionWeight);
    if (state.dielectricMix.firstSampleWeight
            + state.dielectricMix.secondSampleWeight <= 0.0) {
        result.pdf = 0.0;
    } else {
        result.pdf = lerp(
                base.pdf,
                transmission.pdf,
                state.dielectricMix.secondSampleWeight);
    }
    return result;
}

public PrimeCompactFoliageComponents primeCompactFoliageEvaluateComponents(
        float3 wi, float3 wo, PrimeCompactFoliageState state) {
    PrimeCompactOpaqueComponents base = primeCompactOpaqueEvaluateComponents(
            wi, wo, state.base);
    PrimeCompactFoliageComponents result;
    if (state.base.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR) {
        result.diffuse = base.diffuse;
        result.specular = base.specular;
        result.pdf = base.pdf;
        return result;
    }
    PrimeCompactTransmissionEvaluation transmission = primeCompactTransmissionEvaluate(
            wi,
            wo,
            primeCompactFoliageTransmissionState(state),
            PRIME_RC_FLAG_SPECULAR | PRIME_RC_FLAG_DELTA);
    result.diffuse = base.diffuse * (1.0 - state.transmissionWeight);
    result.specular = base.specular * (1.0 - state.transmissionWeight)
            + transmission.throughput.value * state.transmissionWeight;
    if (state.dielectricMix.firstSampleWeight
            + state.dielectricMix.secondSampleWeight <= 0.0) {
        result.pdf = 0.0;
    } else {
        PrimeCompactTransmissionEvaluation fullTransmission =
                primeCompactTransmissionEvaluate(
                        wi,
                        wo,
                        primeCompactFoliageTransmissionState(state),
                        PRIME_RC_FLAG_ALL);
        result.pdf = lerp(
                base.pdf,
                fullTransmission.pdf,
                state.dielectricMix.secondSampleWeight);
    }
    return result;
}

public PrimeRcSampleResult primeCompactFoliageSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactFoliageState state,
        PrimeRcVolumeStack stack) {
    if (state.base.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR) {
        return primeCompactOpaqueSample(wi, randomValue, state.base, stack);
    }
    if (state.dielectricMix.firstSampleWeight
            + state.dielectricMix.secondSampleWeight <= 0.0) {
        PrimeRcSampleResult result;
        result.bsdfSample = primeRcZeroSample();
        result.volumeStack = stack;
        result.rayT = state.base.rayT;
        return result;
    }
    if (randomValue.z <= state.dielectricMix.secondSampleWeight
            && state.dielectricMix.secondSampleWeight > 0.0) {
        PrimeRcSampleResult result = primeCompactTransmissionSample(
                wi,
                float3(
                        randomValue.xy,
                        randomValue.z / state.dielectricMix.secondSampleWeight),
                primeCompactFoliageTransmissionState(state),
                PRIME_RC_FLAG_ALL,
                stack);
        result.bsdfSample.throughput = primeRcThroughputScale(
                result.bsdfSample.throughput, state.transmissionWeight);
        result.bsdfSample.pdf *= state.dielectricMix.secondSampleWeight;
        return result;
    }
    PrimeRcSampleResult result = primeCompactOpaqueSampleWithEonDiffuse(
            wi,
            float3(
                    randomValue.xy,
                    (randomValue.z - state.dielectricMix.secondSampleWeight)
                    / state.dielectricMix.firstSampleWeight),
            state.base,
            stack);
    result.bsdfSample.throughput = primeRcThroughputScale(
            result.bsdfSample.throughput, 1.0 - state.transmissionWeight);
    result.bsdfSample.pdf *= state.dielectricMix.firstSampleWeight;
    return result;
}

public PrimeCompactFoliageEnergy primeCompactFoliageEnergy<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        float3 wi,
        PrimeCompactFoliageState state)
    where TransmissionGgxEnergy : IPrimeRcTransmissionGgxEnergy
{
    PrimeCompactOpaqueEnergy base = primeCompactOpaqueEnergy(wi, state.base);
    PrimeCompactFoliageEnergy result;
    if (state.base.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR) {
        result.diffuse = base.diffuse;
        result.specular = base.specular;
        return result;
    }
    result.diffuse = base.diffuse * (1.0 - state.transmissionWeight);
    result.specular = base.specular * (1.0 - state.transmissionWeight)
            + primeCompactTransmissionEnergy(
                    transmissionGgxEnergy,
                    wi,
                    primeCompactFoliageTransmissionState(state),
                    PRIME_RC_FLAG_SPECULAR | PRIME_RC_FLAG_DELTA)
                    * state.transmissionWeight;
    return result;
}
