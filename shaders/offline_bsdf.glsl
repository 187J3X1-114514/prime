#ifndef PRIME_OFFLINE_BSDF_GLSL
#define PRIME_OFFLINE_BSDF_GLSL

// Offline transport samples the complete transmissive closure once. Unlike the realtime
// adapter, no event is split or forced, so the reference PDF includes reflection/transmission
// selection and remains directly usable by throughput and MIS.
PrimeTransmissiveBsdfSample primeSampleOfflineMinecraftTransmissionFromState(
        PrimeRcState state,
        vec3 baseColor,
        float opacity,
        uint materialFlags,
        vec3 viewDirection,
        vec3 sampleValue,
        PrimeRcVolumeStack volumeStack) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
    state.samplingFlags = PRIME_RC_FLAG_ALL;
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    PrimeRcSampleResult sampled = primeRcPrimeTransmissionSample(
            localView, sampleValue, state, volumeStack);
    if (!primeRcHasSample(sampled.bsdfSample)) {
        return result;
    }
    result.bsdfSample.direction = primeRcOnbToWorld(
            state.material.geometry.onb, sampled.bsdfSample.wo);
    result.bsdfSample.response = sampled.bsdfSample.throughput.value;
    result.bsdfSample.pdf = sampled.bsdfSample.pdf;
    result.bsdfSample.relativeEta = sampled.bsdfSample.eta;
    result.bsdfSample.eventFlags = primeRcToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    if ((result.bsdfSample.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u) {
        result.bsdfSample.response *= primeMinecraftSurfaceTransmittance(
                baseColor, opacity, materialFlags);
    }
    result.volumeStack = sampled.volumeStack;
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (result.bsdfSample.eventFlags == 0u) {
        result.volumeStack = volumeStack;
    }
    return result;
}

BsdfEvaluation primeEvaluateOfflineMinecraftTransmission(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 scatterDirection,
        PrimeRcVolumeStack volumeStack) {
    vec3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
    PrimeRcState state = primeMinecraftTransmissionState(
            surface.baseColor,
            primeSurfaceOpacity(surface),
            outwardNormal,
            surface.materialFlags,
            surface.labPbrNormal,
            surface.labPbrSpecular,
            viewDirection,
            surface.t,
            volumeStack);
    state.samplingFlags = PRIME_RC_FLAG_ALL;
    vec3 localView = primeRcOnbToLocal(state.material.geometry.onb, viewDirection);
    vec3 localScatter = primeRcOnbToLocal(
            state.material.geometry.onb, scatterDirection);
    PrimeRcEval evaluated = primeRcPrimeTransmissionEvaluate(
            localView, localScatter, state);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (evaluated.throughput.flags != PRIME_RC_FLAG_NONE) {
        result.response = evaluated.throughput.value;
        result.pdf = evaluated.pdf;
        if (primeRcIsTransmissive(evaluated.throughput.flags)) {
            result.response *= primeMinecraftSurfaceTransmittance(
                    surface.baseColor,
                    primeSurfaceOpacity(surface),
                    surface.materialFlags);
        }
    }
    return primeSanitizeBsdfEvaluation(result);
}

// Offline transport has one global guaranteed continuation. Interface type never grants an
// additional roulette exemption, including smooth delta reflection and refraction chains.
bool primeOfflineSkipsRussianRoulette(BsdfSample bsdf) {
    return false;
}

bool primeOfflineHasNonDeltaLobe(
        uint materialFlags,
        vec3 baseColor,
        float linearRoughness) {
    if (linearRoughness > 0.0
            || primeMaterialIsFoliage(materialFlags)) {
        return true;
    }
    return !primeMaterialIsTransmissive(materialFlags)
            && (materialFlags & PRIME_MATERIAL_FLAG_LABPBR_METAL) == 0u
            && any(greaterThan(baseColor, vec3(0.0)));
}

#endif
