#ifndef PRIME_BSDF_SPECIALIZATIONS_GLSL
#define PRIME_BSDF_SPECIALIZATIONS_GLSL

#include "robocute_bsdf_openpbr.glsl"

// RoboCute's refractive core already encodes the interface side in the sign of wi.z. Its new
// thick-glass wrapper rotates an exit back to the positive hemisphere without changing the IOR,
// which turns exit into a second entry. Keep the signed interface state and recompute the LUT
// quantity that the reference initializer derived from the rotated direction.
PrimeRcState primeRcPrimeTransmissionInterfaceState(vec3 wi, PrimeRcState state) {
    if (state.geometryThinWalled != 0u || state.entering != 0u) {
        return state;
    }
    state.transmissionMultipleScattering = primeRcMicrofacetDielectricMsCompensation(
            state.specularMicrofacet, wi.z, state.specularFresnel.ior);
    return state;
}

// Bypass only the inconsistent wrapper rotation while retaining state.entering for Prime's
// medium lifecycle. The protected refractive sample/eval/pdf then all observe the same signed wi.
PrimeRcState primeRcPrimeTransmissionClosureState(PrimeRcState state) {
    if (state.geometryThinWalled == 0u) {
        state.entering = 1u;
    }
    return state;
}

PrimeRcThroughput primeRcPrimeTransmissionEval(
        vec3 wi, vec3 wo, PrimeRcState state) {
    return primeRcTransmissionEval(
            wi, wo, primeRcPrimeTransmissionClosureState(state));
}

float primeRcPrimeTransmissionPdf(vec3 wi, vec3 wo, PrimeRcState state) {
    return primeRcTransmissionPdf(
            wi, wo, primeRcPrimeTransmissionClosureState(state));
}

PrimeRcEval primeRcPrimeTransmissionEvaluate(
        vec3 wi, vec3 wo, PrimeRcState state) {
    PrimeRcEval result;
    result.throughput = primeRcPrimeTransmissionEval(wi, wo, state);
    result.pdf = primeRcPrimeTransmissionPdf(wi, wo, state);
    return result;
}

// Prime retains a two-entry medium stack rather than RoboCute's nested-priority integrator.
// The updated reference closure owns only interface sampling, so lifecycle changes remain here.
PrimeRcSampleResult primeRcPrimeTransmissionSample(
        vec3 wi,
        vec3 randomValue,
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

// A zero-event reflective base has no finite solid-angle contribution. The
// reference closures still evaluate Fresnel at micro-cosine zero and then
// multiply by zero, which can turn an exact support boundary into 0 * NaN.
// Prime resolves the event before Fresnel without changing valid-event math.
PrimeRcThroughput primeRcPrimeSpecularEval(
        vec3 wi, vec3 wo, PrimeRcState state) {
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
        vec3 wi, vec3 wo, PrimeRcState state) {
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
        result.value *= vec3(1.0) + state.conductorFresnel.f0
                * (1.0 - state.conductorEss) / state.conductorEss;
    }
    return result;
}

PrimeRcThroughput primeRcPrimeBasicGlossyEval(
        vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    return primeRcLayerEvalValues(
            primeRcLambertEval(wi, wo, state),
            weight > 0.0
                    ? primeRcPrimeSpecularEval(wi, wo, state)
                    : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcSpecularTintOut(wo, state) : vec3(0.0),
            state.basicGlossy,
            weight);
}

PrimeRcThroughput primeRcPrimeBasicMetallicEval(
        vec3 wi, vec3 wo, PrimeRcState state) {
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

PrimeRcEval primeRcPrimeBasicMetallicEvaluate(
        vec3 wi, vec3 wo, PrimeRcState state) {
    PrimeRcEval result;
    result.throughput = primeRcPrimeBasicMetallicEval(wi, wo, state);
    result.pdf = primeRcBasicMetallicPdf(wi, wo, state);
    return result;
}

PrimeRcThroughput primeRcPrimeSubsurfaceGlossyEval(
        vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    return primeRcLayerEvalValues(
            primeRcSubsurfaceEval(wi, wo, state),
            weight > 0.0
                    ? primeRcPrimeSpecularEval(wi, wo, state)
                    : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcSpecularTintOut(wo, state) : vec3(0.0),
            state.subsurfaceGlossy,
            weight);
}

PrimeRcEval primeRcPrimeSubsurfaceGlossyEvaluate(
        vec3 wi, vec3 wo, PrimeRcState state) {
    PrimeRcEval result;
    result.throughput = primeRcPrimeSubsurfaceGlossyEval(wi, wo, state);
    result.pdf = primeRcSubsurfaceGlossyPdf(wi, wo, state);
    return result;
}

// Prime adapter: the reachable OpenPBR subset for thin Minecraft surfaces stops at BaseSubstrate.
// The imported RoboCute implementation remains untouched; these wrappers only select its existing
// composition nodes when Prime has authored no coat, fuzz, diffraction, or thin-film layers.
PrimeRcThroughput primeRcPrimeGlossyDiffuseEval(
        vec3 wi, vec3 wo, PrimeRcState state) {
    float weight = state.material.weight.specular > 0.0 ? 1.0 : 0.0;
    return primeRcLayerEvalValues(
            primeRcMixedDiffuseEval(wi, wo, state),
            weight > 0.0
                    ? primeRcPrimeSpecularEval(wi, wo, state)
                    : primeRcZeroThroughput(),
            weight > 0.0 ? primeRcSpecularTintOut(wo, state) : vec3(0.0),
            state.glossyDiffuse,
            weight);
}

PrimeRcThroughput primeRcPrimeDielectricBaseEval(
        vec3 wi, vec3 wo, PrimeRcState state) {
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

PrimeRcThroughput primeRcPrimeThinWallEval(vec3 wi, vec3 wo, PrimeRcState state) {
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

PrimeRcSampleResult primeRcPrimeThinWallSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    return primeRcBaseSubstrateSample(wi, randomValue, state, stack);
}

float primeRcPrimeThinWallPdf(vec3 wi, vec3 wo, PrimeRcState state) {
    return primeRcBaseSubstratePdf(wi, wo, state);
}

PrimeRcEval primeRcPrimeThinWallEvaluate(vec3 wi, vec3 wo, PrimeRcState state) {
    PrimeRcEval result;
    result.throughput = primeRcPrimeThinWallEval(wi, wo, state);
    result.pdf = primeRcPrimeThinWallPdf(wi, wo, state);
    return result;
}

PrimeRcState primeRcPrimeThinWallStateInit(
        PrimeRcMaterial material,
        vec3 wi,
        float inverseOutsideIor,
        float rayT,
        vec3 wavelengthsNm,
        uint heroWavelengthIndex,
        uint detail,
        uint spectrumed) {
    PrimeRcState state = primeRcBaseState(
            material, wi, inverseOutsideIor, rayT, wavelengthsNm,
            heroWavelengthIndex, detail, spectrumed, false);

    vec3 diffuseEnergy = state.material.weight.subsurface < 1.0
            ? primeRcDiffuseEnergy(wi, state) : vec3(0.0);
    vec3 subsurfaceEnergy = state.material.weight.subsurface > 0.0
            ? primeRcSubsurfaceEnergy(wi, state) : vec3(0.0);
    state.mixedDiffuse = primeRcMakeMixState(
            state.material.weight.subsurface, diffuseEnergy, subsurfaceEnergy);

    vec3 mixedEnergy = primeRcMixedDiffuseEnergy(wi, state);
    state.glossyDiffuse = state.material.weight.specular > 0.0
            ? primeRcMakeLayerState(
                    1.0,
                    mixedEnergy,
                    primeRcSpecularTrans(wi, state, mixedEnergy),
                    primeRcSpecularEnergy(wi, state))
            : primeRcMakeLayerState(
                    0.0, vec3(0.0), vec3(0.0), vec3(0.0));

    if (state.material.weight.transmission > 0.0) {
        state = primeRcInitializeTransmission(wi, state);
    }
    state.dielectricBase = primeRcMakeMixState(
            state.material.weight.transmission,
            state.material.weight.transmission < 1.0
                    ? primeRcGlossyDiffuseEnergy(wi, state) : vec3(0.0),
            state.material.weight.transmission > 0.0
                    ? primeRcTransmissionEnergy(wi, state) : vec3(0.0));

    if (state.material.weight.metalness > 0.0) {
        state = primeRcInitializeConductor(wi, state);
    }
    state.baseSubstrate = primeRcMakeMixState(
            state.material.weight.metalness,
            state.material.weight.metalness < 1.0
                    ? primeRcDielectricBaseEnergy(wi, state) : vec3(0.0),
            state.material.weight.metalness > 0.0
                    ? primeRcConductorEnergy(wi, state) : vec3(0.0));
    return state;
}

#endif
