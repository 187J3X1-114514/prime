#language slang 2026
module "bsdf/adapter/prime_bsdf_specializations.slang";

import "bsdf/adapter/transmission_ggx_energy.slang";
import "bsdf/core/robocute_closures.slang";
import "bsdf/core/robocute_common.slang";
import "bsdf/core/robocute_fresnel.slang";
import "bsdf/core/robocute_microfacet.slang";
import "bsdf/core/robocute_openpbr.slang";

// Mechanical Slang port of the production-reachable declaration closure from
// prime_bsdf_specializations.glsl at RoboCute commit 0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8.

public PrimeRcState primeRcPrimeTransmissionInterfaceState(
        float3 wi, PrimeRcState state) {
    if (state.geometryThinWalled != 0u || state.entering != 0u) {
        return state;
    }
    state.transmissionMultipleScattering = primeRcMicrofacetDielectricMsCompensation(
            primeTransmissionGgxEnergy(),
            state.specularMicrofacet, wi.z, state.specularFresnel.ior);
    return state;
}

PrimeRcState primeRcPrimeTransmissionClosureState(PrimeRcState state) {
    if (state.geometryThinWalled == 0u) {
        state.entering = 1u;
    }
    return state;
}

PrimeRcThroughput primeRcPrimeTransmissionEval(
        float3 wi, float3 wo, PrimeRcState state) {
    return primeRcTransmissionEval(
            wi, wo, primeRcPrimeTransmissionClosureState(state));
}

float primeRcPrimeTransmissionPdf(float3 wi, float3 wo, PrimeRcState state) {
    return primeRcTransmissionPdf(
            wi, wo, primeRcPrimeTransmissionClosureState(state));
}

public PrimeRcEval primeRcPrimeTransmissionEvaluate(
        float3 wi, float3 wo, PrimeRcState state) {
    PrimeRcEval result;
    result.throughput = primeRcPrimeTransmissionEval(wi, wo, state);
    result.pdf = primeRcPrimeTransmissionPdf(wi, wo, state);
    return result;
}

public PrimeRcSampleResult primeRcPrimeTransmissionSample(
        float3 wi,
        float3 randomValue,
        PrimeRcState state,
        PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result = primeRcTransmissionSample(
            wi,
            randomValue,
            primeRcPrimeTransmissionClosureState(state),
            stack);
    if (state.geometryThinWalled == 0u
            && primeRcIsTransmissive(result.bsdfSample.throughput.flags)) {
        if (state.entering != 0u) {
            PrimeRcVolume volume = state.transmissionVolume;
            volume.ior = state.originalIor;
            primeRcStackPush(result.volumeStack, volume);
        } else if (result.volumeStack.count > 0u) {
            result.rayT = 0.0;
            primeRcStackPop(result.volumeStack);
        }
    }
    return result;
}

PrimeRcThroughput primeRcPrimeSpecularEval(
        float3 wi, float3 wo, PrimeRcState state) {
    PrimeRcReflectiveEvalBase base = primeRcReflectiveEvalBase(
            wi, wo, state.specularMicrofacet,
            state.samplingFlags, PRIME_RC_FLAG_REFLECTION);
    if (base.flags == PRIME_RC_FLAG_NONE) {
        return primeRcZeroThroughput();
    }
    PrimeRcThroughput result;
    result.value = primeRcSpecularFresnelUnpolarized(
            state.specularFresnel,
            state.wavelengthsNm,
            state.spectrumed,
            base.microCosine) * base.factor;
    result.flags = base.flags;
    if (primeRcIsNonDelta(result.flags)) {
        result.value *= state.specularMultipleScattering.x;
    }
    return result;
}

PrimeRcThroughput primeRcPrimeConductorEval(
        float3 wi, float3 wo, PrimeRcState state) {
    PrimeRcReflectiveEvalBase base = primeRcReflectiveEvalBase(
            wi, wo, state.specularMicrofacet,
            state.samplingFlags, PRIME_RC_FLAG_REFLECTION);
    if (base.flags == PRIME_RC_FLAG_NONE) {
        return primeRcZeroThroughput();
    }
    PrimeRcThroughput result;
    result.value = primeRcConductorFresnelUnpolarized(
            state.conductorFresnel,
            state.wavelengthsNm,
            state.spectrumed,
            base.microCosine) * base.factor;
    result.flags = base.flags;
    if (primeRcIsNonDelta(result.flags)) {
        result.value *= float3(1.0) + state.conductorFresnel.f0
                * (1.0 - state.conductorEss) / state.conductorEss;
    }
    return result;
}

PrimeRcThroughput primeRcPrimeBasicGlossyEval(
        float3 wi, float3 wo, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    return primeRcLayerEvalValues(
            primeRcLambertEval(wi, wo, state),
            weight > 0.0
                    ? primeRcPrimeSpecularEval(wi, wo, state)
                    : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcSpecularTintOut(wo, state) : float3(0.0),
            state.basicGlossy,
            weight);
}

public PrimeRcThroughput primeRcPrimeBasicMetallicEval(
        float3 wi, float3 wo, PrimeRcState state) {
    float weight = state.material.weight.metalness;
    return primeRcMixEvalValues(
            weight < 1.0
                    ? primeRcPrimeBasicGlossyEval(wi, wo, state)
                    : primeRcZeroThroughput(),
            weight > 0.0
                    ? primeRcPrimeConductorEval(wi, wo, state)
                    : primeRcZeroThroughput(),
            weight);
}

public PrimeRcEval primeRcPrimeBasicMetallicEvaluate(
        float3 wi, float3 wo, PrimeRcState state) {
    PrimeRcEval result;
    result.throughput = primeRcPrimeBasicMetallicEval(wi, wo, state);
    result.pdf = primeRcBasicMetallicPdf(wi, wo, state);
    return result;
}

public PrimeRcThroughput primeRcPrimeSubsurfaceGlossyEval(
        float3 wi, float3 wo, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    return primeRcLayerEvalValues(
            primeRcSubsurfaceEval(wi, wo, state),
            weight > 0.0
                    ? primeRcPrimeSpecularEval(wi, wo, state)
                    : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcSpecularTintOut(wo, state) : float3(0.0),
            state.subsurfaceGlossy,
            weight);
}

public PrimeRcEval primeRcPrimeSubsurfaceGlossyEvaluate(
        float3 wi, float3 wo, PrimeRcState state) {
    PrimeRcEval result;
    result.throughput = primeRcPrimeSubsurfaceGlossyEval(wi, wo, state);
    result.pdf = primeRcSubsurfaceGlossyPdf(wi, wo, state);
    return result;
}

PrimeRcThroughput primeRcPrimeGlossyDiffuseEval(
        float3 wi, float3 wo, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    return primeRcLayerEvalValues(
            primeRcMixedDiffuseEval(wi, wo, state),
            weight > 0.0
                    ? primeRcPrimeSpecularEval(wi, wo, state)
                    : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcSpecularTintOut(wo, state) : float3(0.0),
            state.glossyDiffuse,
            weight);
}

PrimeRcThroughput primeRcPrimeDielectricBaseEval(
        float3 wi, float3 wo, PrimeRcState state) {
    float weight = state.material.weight.transmission;
    return primeRcMixEvalValues(
            weight < 1.0
                    ? primeRcPrimeGlossyDiffuseEval(wi, wo, state)
                    : primeRcZeroThroughput(),
            weight > 0.0
                    ? primeRcPrimeTransmissionEval(wi, wo, state)
                    : primeRcZeroThroughput(),
            weight);
}

public PrimeRcThroughput primeRcPrimeThinWallEval(
        float3 wi, float3 wo, PrimeRcState state) {
    float weight = state.material.weight.metalness;
    return primeRcMixEvalValues(
            weight < 1.0
                    ? primeRcPrimeDielectricBaseEval(wi, wo, state)
                    : primeRcZeroThroughput(),
            weight > 0.0
                    ? primeRcPrimeConductorEval(wi, wo, state)
                    : primeRcZeroThroughput(),
            weight);
}

public PrimeRcSampleResult primeRcPrimeThinWallSample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    return primeRcBaseSubstrateSample(wi, randomValue, state, stack);
}

public float primeRcPrimeThinWallPdf(
        float3 wi, float3 wo, PrimeRcState state) {
    return primeRcBaseSubstratePdf(wi, wo, state);
}

public PrimeRcEval primeRcPrimeThinWallEvaluate(
        float3 wi, float3 wo, PrimeRcState state) {
    PrimeRcEval result;
    result.throughput = primeRcPrimeThinWallEval(wi, wo, state);
    result.pdf = primeRcPrimeThinWallPdf(wi, wo, state);
    return result;
}

public PrimeRcState primeRcPrimeThinWallStateInit(
        PrimeRcMaterial material,
        float3 wi,
        float inverseOutsideIor,
        float rayT,
        float3 wavelengthsNm,
        uint heroWavelengthIndex,
        uint detail,
        uint spectrumed) {
    PrimeRcState state = primeRcBaseState(
            primeTransmissionGgxEnergy(),
            material, wi, inverseOutsideIor, rayT, wavelengthsNm,
            heroWavelengthIndex, detail, spectrumed, false);

    float3 diffuseEnergy = state.material.weight.subsurface < 1.0
            ? primeRcDiffuseEnergy(wi, state) : float3(0.0);
    float3 subsurfaceEnergy = state.material.weight.subsurface > 0.0
            ? primeRcSubsurfaceEnergy(wi, state) : float3(0.0);
    state.mixedDiffuse = primeRcMakeMixState(
            state.material.weight.subsurface, diffuseEnergy, subsurfaceEnergy);

    float3 mixedEnergy = primeRcMixedDiffuseEnergy(wi, state);
    state.glossyDiffuse = state.material.weight.specular > 0.0
            ? primeRcMakeLayerState(
                    1.0,
                    mixedEnergy,
                    primeRcSpecularTrans(wi, state, mixedEnergy),
                    primeRcSpecularEnergy(wi, state))
            : primeRcMakeLayerState(
                    0.0, float3(0.0), float3(0.0), float3(0.0));

    if (state.material.weight.transmission > 0.0) {
        state = primeRcInitializeTransmission(primeTransmissionGgxEnergy(), wi, state);
    }
    state.dielectricBase = primeRcMakeMixState(
            state.material.weight.transmission,
            state.material.weight.transmission < 1.0
                    ? primeRcGlossyDiffuseEnergy(wi, state) : float3(0.0),
            state.material.weight.transmission > 0.0
                    ? primeRcTransmissionEnergy(primeTransmissionGgxEnergy(), wi, state)
                    : float3(0.0));

    if (state.material.weight.metalness > 0.0) {
        state = primeRcInitializeConductor(wi, state);
    }
    state.baseSubstrate = primeRcMakeMixState(
            state.material.weight.metalness,
            state.material.weight.metalness < 1.0
                    ? primeRcDielectricBaseEnergy(
                            primeTransmissionGgxEnergy(), wi, state)
                    : float3(0.0),
            state.material.weight.metalness > 0.0
                    ? primeRcConductorEnergy(wi, state) : float3(0.0));
    return state;
}
