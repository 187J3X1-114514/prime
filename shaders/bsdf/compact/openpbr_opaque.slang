#language slang 2026
module "bsdf/compact/openpbr_opaque.slang";

import "bsdf/compact/openpbr_common.slang";
import "bsdf/compact/openpbr_fresnel.slang";
import "bsdf/compact/openpbr_microfacet.slang";
import "bsdf/core/transmission_ggx_energy.slang";

// Exact OpenPBR opaque specializations used by Prime today: dielectric base with
// specular layering, conductor, and subsurface with specular layering. The compact
// state stores only values reachable from these topologies. RoboCute remains the
// differential reference while this module replaces the generic graph incrementally.

public static const uint PRIME_COMPACT_OPAQUE_DIELECTRIC = 0u;
public static const uint PRIME_COMPACT_OPAQUE_CONDUCTOR = 1u;
public static const uint PRIME_COMPACT_OPAQUE_SUBSURFACE = 2u;
public static const uint PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE = 3u;

public struct PrimeCompactMixState {
    public float firstSampleWeight;
    public float secondSampleWeight;
};

public struct PrimeCompactLayerState {
    public float3 transmittanceIn;
    public float baseSampleWeight;
    public float specularSampleWeight;
};

public struct PrimeCompactOpaqueState {
    public PrimeOpenPbrOnb onb;
    public uint kind;
    public uint thinWalled;
    public float rayT;
    public float3 baseColor;
    public float subsurfaceWeight;
    public float3 subsurfaceColor;
    public float subsurfaceAnisotropy;
    public PrimeOpenPbrMicrofacet microfacet;
    public PrimeOpenPbrSpecularFresnel dielectricFresnel;
    public float3 dielectricMultipleScattering;
    public PrimeOpenPbrConductorFresnel conductorFresnel;
    public float conductorEss;
    public PrimeCompactMixState diffuseMix;
    public PrimeCompactLayerState layer;
};

public struct PrimeCompactOpaqueEvaluation {
    public PrimeOpenPbrThroughput throughput;
    public float pdf;
};

public struct PrimeCompactOpaqueComponents {
    public float3 diffuse;
    public float3 specular;
    public float pdf;
};

public struct PrimeCompactOpaqueEnergy {
    public float3 diffuse;
    public float3 specular;
};

PrimeOpenPbrSampleResult primeCompactZeroSampleResult(
        PrimeCompactOpaqueState state, PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result;
    result.bsdfSample = primeOpenPbrZeroSample();
    result.volumeStack = stack;
    result.rayT = state.rayT;
    return result;
}

PrimeCompactLayerState primeCompactMakeLayerState(
        float3 baseEnergy, float3 transmittanceIn, float3 specularEnergy) {
    PrimeCompactLayerState result;
    result.transmittanceIn = transmittanceIn;
    result.baseSampleWeight = primeOpenPbrSpectrumToWeight(transmittanceIn * baseEnergy);
    result.specularSampleWeight = primeOpenPbrSpectrumToWeight(specularEnergy);
    float sum = result.baseSampleWeight + result.specularSampleWeight;
    if (sum > 0.0) {
        result.baseSampleWeight /= sum;
        result.specularSampleWeight /= sum;
    }
    return result;
}

PrimeCompactMixState primeCompactMakeMixState(
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
    result.firstSampleWeight = (1.0 - weight) * primeOpenPbrSpectrumToWeight(firstEnergy);
    result.secondSampleWeight = weight * primeOpenPbrSpectrumToWeight(secondEnergy);
    float sum = result.firstSampleWeight + result.secondSampleWeight;
    if (sum > 0.0) {
        result.firstSampleWeight /= sum;
        result.secondSampleWeight /= sum;
    }
    return result;
}

PrimeOpenPbrThroughput primeCompactMixEval(
        PrimeOpenPbrThroughput first, PrimeOpenPbrThroughput second, float weight) {
    PrimeOpenPbrThroughput result = primeOpenPbrZeroThroughput();
    if (weight < 1.0) { result = first; }
    if (weight > 0.0) {
        result = primeOpenPbrThroughputScale(result, 1.0 - weight);
        result = primeOpenPbrThroughputAdd(
                result, primeOpenPbrThroughputScale(second, weight));
    }
    return result;
}

PrimeOpenPbrThroughput primeCompactLayerEval(
        PrimeOpenPbrThroughput base,
        PrimeOpenPbrThroughput specular,
        PrimeCompactLayerState layer) {
    PrimeOpenPbrThroughput result = primeOpenPbrThroughputScale(base, layer.transmittanceIn);
    return primeOpenPbrThroughputAdd(result, specular);
}

float3x3 primeCompactEonBasis(float3 wi) {
    float lengthSquared = dot(wi.xy, wi.xy);
    float3 x = lengthSquared > 0.0
            ? float3(wi.x, wi.y, 0.0) * rsqrt(lengthSquared)
            : float3(1.0, 0.0, 0.0);
    float3 y = float3(-x.y, x.x, 0.0);
    return float3x3(x, y, float3(0.0, 0.0, 1.0));
}

PrimeOpenPbrThroughput primeCompactLambertEval(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    if (wi.z < 0.0 || wo.z < 0.0
            || (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return primeOpenPbrZeroThroughput();
    }
    PrimeOpenPbrThroughput result;
    result.value = state.baseColor * (PRIME_OPENPBR_INV_PI * wo.z);
    result.flags = PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION;
    return result;
}

float primeCompactLambertPdf(float3 wi, float3 wo) {
    return wi.z >= 0.0 && wo.z > 0.0 ? PRIME_OPENPBR_INV_PI * wo.z : 0.0;
}

float3 primeCompactLambertEnergy(float3 wi, PrimeCompactOpaqueState state, uint samplingFlags) {
    return wi.z >= 0.0 && (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) != 0u
            ? state.baseColor : float3(0.0);
}

PrimeOpenPbrSampleResult primeCompactLambertSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        uint samplingFlags,
        PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result = primeCompactZeroSampleResult(state, stack);
    if (wi.z < 0.0 || (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return result;
    }
    result.bsdfSample.wo = primeOpenPbrCosineSampleHemisphere(randomValue.xy);
    result.bsdfSample.pdf = PRIME_OPENPBR_INV_PI * result.bsdfSample.wo.z;
    result.bsdfSample.throughput.value = state.baseColor * result.bsdfSample.pdf;
    result.bsdfSample.throughput.flags = PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION;
    return result;
}

// The generic OpenPBR diffuse closure uses EON even at diffuse roughness zero.
// Its value, PDF and energy reduce to Lambert, but its cLTC sampler rotates the
// cosine sample into the incident-direction basis. Preserve that exact mapping
// for fractional diffuse/subsurface mixtures without retaining general EON state.
PrimeOpenPbrSampleResult primeCompactZeroRoughnessEonSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        uint samplingFlags,
        PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result = primeCompactZeroSampleResult(state, stack);
    if (wi.z < 0.0 || (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return result;
    }
    if (randomValue.z <= 0.0) {
        // EON uses <= for its zero-probability uniform branch. Although this has
        // zero measure, preserving it keeps boundary random inputs deterministic.
        result.bsdfSample.wo = primeOpenPbrUniformSampleHemisphere(randomValue.xy);
        result.bsdfSample.pdf = result.bsdfSample.wo.z * PRIME_OPENPBR_INV_PI;
        result.bsdfSample.throughput = primeCompactLambertEval(
                wi, result.bsdfSample.wo, state, samplingFlags);
        return result;
    }
    float radius = sqrt(randomValue.x);
    float phi = 2.0 * PRIME_OPENPBR_PI * randomValue.y;
    float x = -radius * cos(phi);
    float y = radius * sin(phi);
    float3 wo = float3(x, y, sqrt(max(1.0 - (x * x + y * y), 0.0)));
    float woLength = length(wo);
    result.bsdfSample.pdf = wo.z * PRIME_OPENPBR_INV_PI
            * woLength * woLength * woLength;
    result.bsdfSample.wo = normalize(mul(wo, primeCompactEonBasis(wi)));
    result.bsdfSample.throughput = primeCompactLambertEval(
            wi, result.bsdfSample.wo, state, samplingFlags);
    return result;
}

PrimeOpenPbrThroughput primeCompactSubsurfaceEval(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    bool reflected = wo.z * wi.z > 0.0;
    if ((reflected && ((samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) == 0u
            || state.thinWalled == 0u))
            || (!reflected && (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION) == 0u)) {
        return primeOpenPbrZeroThroughput();
    }
    PrimeOpenPbrThroughput result;
    result.value = float3(PRIME_OPENPBR_INV_PI * abs(wo.z));
    if (state.thinWalled != 0u) {
        result.value *= state.subsurfaceColor * 0.5
                * (reflected
                        ? 1.0 - state.subsurfaceAnisotropy
                        : 1.0 + state.subsurfaceAnisotropy);
    }
    result.flags = reflected
            ? PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION
            : PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION;
    return result;
}

float primeCompactSubsurfaceReflectionProbability(
        PrimeCompactOpaqueState state, uint samplingFlags) {
    float probability = state.thinWalled != 0u
            ? (1.0 - state.subsurfaceAnisotropy) * 0.5 : 0.0;
    return primeOpenPbrIsReflective(samplingFlags) ? probability : 0.0;
}

float primeCompactSubsurfaceTransmissionProbability(
        PrimeCompactOpaqueState state, uint samplingFlags) {
    float probability = state.thinWalled != 0u
            ? (1.0 + state.subsurfaceAnisotropy) * 0.5 : 1.0;
    return primeOpenPbrIsTransmissive(samplingFlags) ? probability : 0.0;
}

float primeCompactSubsurfacePdf(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    float reflectionProbability = primeCompactSubsurfaceReflectionProbability(
            state, samplingFlags);
    float transmissionProbability = primeCompactSubsurfaceTransmissionProbability(
            state, samplingFlags);
    if (reflectionProbability == 0.0 && transmissionProbability == 0.0) {
        return 0.0;
    }
    return PRIME_OPENPBR_INV_PI * abs(wo.z)
            * (wi.z * wo.z > 0.0 ? reflectionProbability : transmissionProbability);
}

float3 primeCompactSubsurfaceEnergy(
        PrimeCompactOpaqueState state, uint samplingFlags) {
    if ((samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) != 0u) {
        return state.thinWalled == 0u ? float3(1.0) : state.subsurfaceColor;
    }
    return float3(0.0);
}

PrimeOpenPbrSampleResult primeCompactSubsurfaceSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        uint samplingFlags,
        PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result = primeCompactZeroSampleResult(state, stack);
    if ((samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE) == 0u) { return result; }
    float reflectionProbability = primeCompactSubsurfaceReflectionProbability(
            state, samplingFlags);
    float transmissionProbability = primeCompactSubsurfaceTransmissionProbability(
            state, samplingFlags);
    if (reflectionProbability == 0.0 && transmissionProbability == 0.0) {
        return result;
    }
    result.bsdfSample.wo = primeOpenPbrCosineSampleHemisphere(randomValue.xy);
    result.bsdfSample.pdf = PRIME_OPENPBR_INV_PI * result.bsdfSample.wo.z;
    result.bsdfSample.throughput.value = float3(result.bsdfSample.pdf);
    if (wi.z < 0.0) { result.bsdfSample.wo.z = -result.bsdfSample.wo.z; }
    if (randomValue.z <= transmissionProbability) {
        result.bsdfSample.wo.z = -result.bsdfSample.wo.z;
        result.bsdfSample.pdf *= transmissionProbability;
        result.bsdfSample.throughput.value *= transmissionProbability;
        result.bsdfSample.throughput.flags = PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION;
        if (state.thinWalled != 0u) {
            result.bsdfSample.throughput.value *= state.subsurfaceColor;
        }
    } else {
        result.bsdfSample.pdf *= reflectionProbability;
        result.bsdfSample.throughput.value *= reflectionProbability * state.subsurfaceColor;
        result.bsdfSample.throughput.flags = PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION;
    }
    return result;
}

PrimeOpenPbrThroughput primeCompactMixedDiffuseEval(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    return primeCompactMixEval(
            state.subsurfaceWeight < 1.0
                    ? primeCompactLambertEval(wi, wo, state, samplingFlags)
                    : primeOpenPbrZeroThroughput(),
            state.subsurfaceWeight > 0.0
                    ? primeCompactSubsurfaceEval(wi, wo, state, samplingFlags)
                    : primeOpenPbrZeroThroughput(),
            state.subsurfaceWeight);
}

float primeCompactMixedDiffusePdf(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    if (state.diffuseMix.firstSampleWeight + state.diffuseMix.secondSampleWeight <= 0.0) {
        return 0.0;
    }
    float first = state.subsurfaceWeight < 1.0
            && (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) != 0u
            ? primeCompactLambertPdf(wi, wo) : 0.0;
    float second = state.subsurfaceWeight > 0.0
            ? primeCompactSubsurfacePdf(wi, wo, state, samplingFlags) : 0.0;
    return lerp(first, second, state.diffuseMix.secondSampleWeight);
}

float3 primeCompactMixedDiffuseEnergy(
        float3 wi, PrimeCompactOpaqueState state, uint samplingFlags) {
    float3 first = state.subsurfaceWeight < 1.0
            && (samplingFlags & PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION) != 0u
            ? state.baseColor : float3(0.0);
    float3 second = state.subsurfaceWeight > 0.0
            ? primeCompactSubsurfaceEnergy(state, samplingFlags) : float3(0.0);
    float3 result = float3(0.0);
    if (state.subsurfaceWeight < 1.0) { result = first; }
    if (state.subsurfaceWeight > 0.0) {
        result = result * (1.0 - state.subsurfaceWeight)
                + second * state.subsurfaceWeight;
    }
    return result;
}

PrimeOpenPbrSampleResult primeCompactMixedDiffuseSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        uint samplingFlags,
        PrimeOpenPbrVolumeStack stack) {
    if (state.diffuseMix.firstSampleWeight + state.diffuseMix.secondSampleWeight <= 0.0) {
        return primeCompactZeroSampleResult(state, stack);
    }
    if (randomValue.z <= state.diffuseMix.secondSampleWeight
            && state.diffuseMix.secondSampleWeight > 0.0) {
        PrimeOpenPbrSampleResult result = primeCompactSubsurfaceSample(
                wi,
                float3(randomValue.xy,
                        randomValue.z / state.diffuseMix.secondSampleWeight),
                state,
                samplingFlags,
                stack);
        result.bsdfSample.throughput = primeOpenPbrThroughputScale(
                result.bsdfSample.throughput, state.subsurfaceWeight);
        result.bsdfSample.pdf *= state.diffuseMix.secondSampleWeight;
        return result;
    }
    PrimeOpenPbrSampleResult result = primeCompactZeroRoughnessEonSample(
            wi,
            float3(randomValue.xy,
                    (randomValue.z - state.diffuseMix.secondSampleWeight)
                    / state.diffuseMix.firstSampleWeight),
            state,
            samplingFlags,
            stack);
    result.bsdfSample.throughput = primeOpenPbrThroughputScale(
            result.bsdfSample.throughput, 1.0 - state.subsurfaceWeight);
    result.bsdfSample.pdf *= state.diffuseMix.firstSampleWeight;
    return result;
}

PrimeOpenPbrThroughput primeCompactDielectricEval(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    PrimeOpenPbrReflectiveEvalBase base = primeOpenPbrReflectiveEvalBase(
            wi, wo, state.microfacet, samplingFlags, PRIME_OPENPBR_FLAG_REFLECTION);
    if (base.flags == PRIME_OPENPBR_FLAG_NONE) { return primeOpenPbrZeroThroughput(); }
    PrimeOpenPbrThroughput result;
    result.value = primeOpenPbrSpecularFresnelUnpolarized(
            state.dielectricFresnel, float3(0.0), 0u, base.microCosine) * base.factor;
    result.flags = base.flags;
    if (primeOpenPbrIsNonDelta(result.flags)) {
        result.value *= state.dielectricMultipleScattering.x;
    }
    return result;
}

float primeCompactDielectricPdf(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    return primeOpenPbrReflectivePdf(
            wi, wo, state.microfacet, samplingFlags, PRIME_OPENPBR_FLAG_REFLECTION);
}

float3 primeCompactDielectricEnergy(
        float3 wi, PrimeCompactOpaqueState state, uint samplingFlags) {
    if (wi.z < 0.0) { return float3(0.0); }
    if (!primeOpenPbrMicrofacetEffectivelySmooth(state.microfacet)
            && state.dielectricFresnel.ior != 1.0) {
        if (!primeOpenPbrIsSpecular(samplingFlags)) { return float3(0.0); }
    } else if (!primeOpenPbrIsDelta(samplingFlags)) {
        return float3(0.0);
    }
    if (!primeOpenPbrIsReflective(samplingFlags)) { return float3(0.0); }
    return state.dielectricMultipleScattering.z * state.dielectricFresnel.color;
}

PrimeOpenPbrSampleResult primeCompactDielectricSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        uint samplingFlags,
        PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result = primeCompactZeroSampleResult(state, stack);
    PrimeOpenPbrReflectiveSampleBase base = primeOpenPbrReflectiveSampleBase(
            wi, randomValue, state.microfacet, samplingFlags, PRIME_OPENPBR_FLAG_REFLECTION);
    result.bsdfSample = base.bsdfSample;
    result.bsdfSample.throughput.value *= primeOpenPbrSpecularFresnelUnpolarized(
            state.dielectricFresnel, float3(0.0), 0u, base.microCosine);
    if (primeOpenPbrIsNonDelta(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *= state.dielectricMultipleScattering.x;
    }
    return result;
}

PrimeOpenPbrThroughput primeCompactConductorEval(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    PrimeOpenPbrReflectiveEvalBase base = primeOpenPbrReflectiveEvalBase(
            wi, wo, state.microfacet, samplingFlags, PRIME_OPENPBR_FLAG_REFLECTION);
    if (base.flags == PRIME_OPENPBR_FLAG_NONE) { return primeOpenPbrZeroThroughput(); }
    PrimeOpenPbrThroughput result;
    result.value = primeOpenPbrConductorFresnelUnpolarized(
            state.conductorFresnel, float3(0.0), 0u, base.microCosine) * base.factor;
    result.flags = base.flags;
    if (primeOpenPbrIsNonDelta(result.flags)) {
        result.value *= float3(1.0) + state.conductorFresnel.f0
                * (1.0 - state.conductorEss) / state.conductorEss;
    }
    return result;
}

float primeCompactConductorPdf(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    return primeOpenPbrReflectivePdf(
            wi, wo, state.microfacet, samplingFlags, PRIME_OPENPBR_FLAG_REFLECTION);
}

float3 primeCompactConductorEnergy(
        float3 wi, PrimeCompactOpaqueState state, uint samplingFlags) {
    if (wi.z < 0.0) { return float3(0.0); }
    if (primeOpenPbrMicrofacetEffectivelySmooth(state.microfacet)) {
        if ((samplingFlags & PRIME_OPENPBR_FLAG_DELTA_REFLECTION) == 0u) {
            return float3(0.0);
        }
    } else if ((samplingFlags & PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION) == 0u) {
        return float3(0.0);
    }
    float3 f0 = state.conductorFresnel.energyF0;
    return primeOpenPbrMicrofacetDirectionalAlbedoReflection(
            state.microfacet,
            wi.z,
            f0,
            clamp(float3(state.conductorFresnel.weight), float3(0.0), float3(1.0)))
            * (float3(1.0) + f0 * (1.0 - state.conductorEss) / state.conductorEss);
}

PrimeOpenPbrSampleResult primeCompactConductorSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        uint samplingFlags,
        PrimeOpenPbrVolumeStack stack) {
    PrimeOpenPbrSampleResult result = primeCompactZeroSampleResult(state, stack);
    PrimeOpenPbrReflectiveSampleBase base = primeOpenPbrReflectiveSampleBase(
            wi, randomValue, state.microfacet, samplingFlags, PRIME_OPENPBR_FLAG_REFLECTION);
    result.bsdfSample = base.bsdfSample;
    result.bsdfSample.throughput.value *= primeOpenPbrConductorFresnelUnpolarized(
            state.conductorFresnel, float3(0.0), 0u, base.microCosine);
    if (primeOpenPbrIsNonDelta(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *= float3(1.0) + state.conductorFresnel.f0
                * (1.0 - state.conductorEss) / state.conductorEss;
    }
    return result;
}

PrimeOpenPbrThroughput primeCompactOpaqueLayerEval(
        float3 wi, float3 wo, PrimeCompactOpaqueState state, uint samplingFlags) {
    PrimeOpenPbrThroughput base = state.kind == PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE
            ? primeCompactMixedDiffuseEval(wi, wo, state, samplingFlags)
            : state.kind == PRIME_COMPACT_OPAQUE_SUBSURFACE
                    ? primeCompactSubsurfaceEval(wi, wo, state, samplingFlags)
                    : primeCompactLambertEval(wi, wo, state, samplingFlags);
    PrimeOpenPbrThroughput specular = primeCompactDielectricEval(wi, wo, state, samplingFlags);
    return primeCompactLayerEval(base, specular, state.layer);
}

float primeCompactOpaqueLayerPdf(
        float3 wi, float3 wo, PrimeCompactOpaqueState state) {
    float basePdf = state.kind == PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE
            ? primeCompactMixedDiffusePdf(wi, wo, state, PRIME_OPENPBR_FLAG_ALL)
            : state.kind == PRIME_COMPACT_OPAQUE_SUBSURFACE
                    ? primeCompactSubsurfacePdf(wi, wo, state, PRIME_OPENPBR_FLAG_ALL)
                    : primeCompactLambertPdf(wi, wo);
    if (state.layer.baseSampleWeight + state.layer.specularSampleWeight <= 0.0) {
        return 0.0;
    }
    return lerp(
            basePdf,
            primeCompactDielectricPdf(wi, wo, state, PRIME_OPENPBR_FLAG_ALL),
            state.layer.specularSampleWeight);
}

PrimeOpenPbrSampleResult primeCompactOpaqueLayerSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        PrimeOpenPbrVolumeStack stack,
        bool useEonDiffuseMapping) {
    if (state.layer.baseSampleWeight + state.layer.specularSampleWeight <= 0.0) {
        return primeCompactZeroSampleResult(state, stack);
    }
    if (randomValue.z <= state.layer.specularSampleWeight
            && state.layer.specularSampleWeight > 0.0) {
        PrimeOpenPbrSampleResult result = primeCompactDielectricSample(
                wi,
                float3(randomValue.xy, randomValue.z / state.layer.specularSampleWeight),
                state,
                PRIME_OPENPBR_FLAG_ALL,
                stack);
        result.bsdfSample.pdf *= state.layer.specularSampleWeight;
        return result;
    }
    PrimeOpenPbrSampleResult result = state.kind == PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE
            ? primeCompactMixedDiffuseSample(
                    wi,
                    float3(randomValue.xy,
                            (randomValue.z - state.layer.specularSampleWeight)
                            / state.layer.baseSampleWeight),
                    state,
                    PRIME_OPENPBR_FLAG_ALL,
                    stack)
            : state.kind == PRIME_COMPACT_OPAQUE_SUBSURFACE
                    ? primeCompactSubsurfaceSample(
                            wi,
                            float3(randomValue.xy,
                                    (randomValue.z - state.layer.specularSampleWeight)
                                    / state.layer.baseSampleWeight),
                            state,
                            PRIME_OPENPBR_FLAG_ALL,
                            stack)
                    : useEonDiffuseMapping
                            ? primeCompactZeroRoughnessEonSample(
                                    wi,
                                    float3(randomValue.xy,
                                            (randomValue.z
                                                    - state.layer.specularSampleWeight)
                                            / state.layer.baseSampleWeight),
                                    state,
                                    PRIME_OPENPBR_FLAG_ALL,
                                    stack)
                            : primeCompactLambertSample(
                            wi,
                            float3(randomValue.xy,
                                    (randomValue.z - state.layer.specularSampleWeight)
                                    / state.layer.baseSampleWeight),
                            state,
                            PRIME_OPENPBR_FLAG_ALL,
                            stack);
    result.bsdfSample.throughput = primeOpenPbrThroughputScale(
            result.bsdfSample.throughput, state.layer.transmittanceIn);
    result.bsdfSample.pdf *= state.layer.baseSampleWeight;
    return result;
}

public bool primeCompactOpaqueSupports(PrimeOpenPbrMaterial material) {
    return material.weight.base == 1.0
            && material.weight.specular == 1.0
            && (material.weight.metalness == 0.0 || material.weight.metalness == 1.0)
            && material.weight.transmission == 0.0
            && material.weight.coat == 0.0
            && material.weight.fuzz == 0.0
            && material.weight.thinFilm == 0.0
            && material.weight.diffraction == 0.0
            && material.weight.diffuseRoughness == 0.0
            && material.weight.subsurface >= 0.0
            && material.weight.subsurface <= 1.0
            && (material.weight.subsurface == 0.0 || material.weight.metalness == 0.0);
}

public PrimeCompactOpaqueState primeCompactOpaqueStateInit<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        PrimeOpenPbrMaterial material,
        float3 wi,
        float inverseOutsideIor,
        float rayT)
    where TransmissionGgxEnergy : IPrimeRcTransmissionGgxEnergy
{
    PrimeCompactOpaqueState state;
    state.onb = material.geometry.onb;
    state.kind = material.weight.metalness == 1.0
            ? PRIME_COMPACT_OPAQUE_CONDUCTOR
            : material.weight.subsurface == 1.0
                    ? PRIME_COMPACT_OPAQUE_SUBSURFACE
                    : material.weight.subsurface > 0.0
                            ? PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE
                            : PRIME_COMPACT_OPAQUE_DIELECTRIC;
    state.thinWalled = material.geometry.thinWalled;
    state.rayT = rayT;
    state.baseColor = material.base.color;
    state.subsurfaceWeight = material.weight.subsurface;
    state.subsurfaceColor = material.subsurface.color;
    state.subsurfaceAnisotropy = material.subsurface.scatterAnisotropy;
    state.microfacet.alpha = primeOpenPbrSpecularNdfRoughnesses(
            material.specular.roughness, material.specular.roughnessAnisotropy);

    float interfaceIor = material.specular.ior * inverseOutsideIor;
    state.dielectricFresnel.ior = interfaceIor;
    state.dielectricFresnel.energyIor = primeOpenPbrIorAdjustment(
            interfaceIor, material.weight.specular);
    state.dielectricFresnel.color = material.specular.color;
    state.dielectricMultipleScattering = primeOpenPbrMicrofacetDielectricMsCompensation(
            transmissionGgxEnergy,
            state.microfacet,
            wi.z,
            state.dielectricFresnel.energyIor);

    state.conductorFresnel.f0 = material.base.color * material.weight.base;
    state.conductorFresnel.f82 = material.specular.color;
    state.conductorFresnel.f90 = float3(1.0);
    state.conductorFresnel.weight = material.weight.specular;
    state.conductorFresnel.energyF0 = clamp(
            material.base.color * material.weight.base * material.weight.specular,
            float3(0.0),
            float3(1.0));
    state.conductorEss = 1.0;
    if (state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR
            && !primeOpenPbrMicrofacetEffectivelySmooth(state.microfacet)) {
        state.conductorEss = primeOpenPbrMicrofacetDirectionalAlbedoReflection(
                state.microfacet, wi.z, float3(1.0), float3(1.0)).x;
    }

    float3 diffuseEnergy = state.kind == PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE
            && state.subsurfaceWeight < 1.0
            ? state.baseColor
            : float3(0.0);
    float3 subsurfaceEnergy = state.kind != PRIME_COMPACT_OPAQUE_CONDUCTOR
            && state.subsurfaceWeight > 0.0
            ? primeCompactSubsurfaceEnergy(state, PRIME_OPENPBR_FLAG_ALL)
            : float3(0.0);
    state.diffuseMix = primeCompactMakeMixState(
            state.subsurfaceWeight, diffuseEnergy, subsurfaceEnergy);
    float3 baseEnergy = state.kind == PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE
            ? primeCompactMixedDiffuseEnergy(wi, state, PRIME_OPENPBR_FLAG_ALL)
            : state.kind == PRIME_COMPACT_OPAQUE_SUBSURFACE
                    ? subsurfaceEnergy
                    : primeCompactLambertEnergy(wi, state, PRIME_OPENPBR_FLAG_ALL);
    state.layer = primeCompactMakeLayerState(
            baseEnergy,
            float3(1.0 - state.dielectricMultipleScattering.z),
            primeCompactDielectricEnergy(wi, state, PRIME_OPENPBR_FLAG_ALL));
    return state;
}

public PrimeCompactOpaqueEvaluation primeCompactOpaqueEvaluate(
        float3 wi, float3 wo, PrimeCompactOpaqueState state) {
    PrimeCompactOpaqueEvaluation result;
    result.throughput = state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR
            ? primeCompactConductorEval(wi, wo, state, PRIME_OPENPBR_FLAG_ALL)
            : primeCompactOpaqueLayerEval(wi, wo, state, PRIME_OPENPBR_FLAG_ALL);
    result.pdf = state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR
            ? primeCompactConductorPdf(wi, wo, state, PRIME_OPENPBR_FLAG_ALL)
            : primeCompactOpaqueLayerPdf(wi, wo, state);
    return result;
}

public PrimeCompactOpaqueComponents primeCompactOpaqueEvaluateComponents(
        float3 wi, float3 wo, PrimeCompactOpaqueState state) {
    PrimeCompactOpaqueComponents result;
    result.diffuse = float3(0.0);
    result.specular = float3(0.0);
    if (state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR) {
        result.specular = primeCompactConductorEval(
                wi, wo, state, PRIME_OPENPBR_FLAG_SPECULAR | PRIME_OPENPBR_FLAG_DELTA).value;
        result.pdf = primeCompactConductorPdf(wi, wo, state, PRIME_OPENPBR_FLAG_ALL);
        return result;
    }
    result.diffuse = primeCompactOpaqueLayerEval(
            wi, wo, state, PRIME_OPENPBR_FLAG_DIFFUSE).value;
    result.specular = primeCompactOpaqueLayerEval(
            wi, wo, state, PRIME_OPENPBR_FLAG_SPECULAR | PRIME_OPENPBR_FLAG_DELTA).value;
    result.pdf = primeCompactOpaqueLayerPdf(wi, wo, state);
    return result;
}

public PrimeOpenPbrSampleResult primeCompactOpaqueSample(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        PrimeOpenPbrVolumeStack stack) {
    return state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR
            ? primeCompactConductorSample(
                    wi, randomValue, state, PRIME_OPENPBR_FLAG_ALL, stack)
            : primeCompactOpaqueLayerSample(
                    wi, randomValue, state, stack, false);
}

public PrimeOpenPbrSampleResult primeCompactOpaqueSampleWithEonDiffuse(
        float3 wi,
        float3 randomValue,
        PrimeCompactOpaqueState state,
        PrimeOpenPbrVolumeStack stack) {
    return state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR
            ? primeCompactConductorSample(
                    wi, randomValue, state, PRIME_OPENPBR_FLAG_ALL, stack)
            : primeCompactOpaqueLayerSample(
                    wi, randomValue, state, stack, true);
}

public PrimeCompactOpaqueEnergy primeCompactOpaqueEnergy(
        float3 wi, PrimeCompactOpaqueState state) {
    PrimeCompactOpaqueEnergy result;
    result.diffuse = float3(0.0);
    result.specular = float3(0.0);
    if (state.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR) {
        result.specular = primeCompactConductorEnergy(wi, state, PRIME_OPENPBR_FLAG_ALL);
        return result;
    }
    float3 base = state.kind == PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE
            ? primeCompactMixedDiffuseEnergy(wi, state, PRIME_OPENPBR_FLAG_DIFFUSE)
            : state.kind == PRIME_COMPACT_OPAQUE_SUBSURFACE
                    ? primeCompactSubsurfaceEnergy(state, PRIME_OPENPBR_FLAG_DIFFUSE)
                    : primeCompactLambertEnergy(wi, state, PRIME_OPENPBR_FLAG_DIFFUSE);
    result.diffuse = base * state.layer.transmittanceIn;
    result.specular = primeCompactDielectricEnergy(
            wi, state, PRIME_OPENPBR_FLAG_SPECULAR | PRIME_OPENPBR_FLAG_DELTA);
    return result;
}
