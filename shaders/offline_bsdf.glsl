#ifndef PRIME_OFFLINE_BSDF_GLSL
#define PRIME_OFFLINE_BSDF_GLSL

// Offline transport uses the shared complete single-path closure. No event is split or forced, so
// the reference PDF includes reflection/transmission selection and remains directly usable by
// throughput and MIS.
PrimeTransmissiveBsdfSample primeSampleOfflineMinecraftTransmissionFromState(
        PrimeRcState state,
        vec3 baseColor,
        float opacity,
        uint materialFlags,
        vec3 viewDirection,
        vec3 sampleValue,
        PrimeRcVolumeStack volumeStack) {
    return primeSampleMinecraftTransmissionCompleteFromState(
            state,
            baseColor,
            opacity,
            materialFlags,
            viewDirection,
            sampleValue,
            volumeStack);
}

BsdfEvaluation primeEvaluateOfflineMinecraftTransmission(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 scatterDirection,
        PrimeRcVolumeStack volumeStack) {
    vec3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
    PrimeRcState state = primeMinecraftBoundaryTransmissionState(
            surface.baseColor,
            primeSurfaceOpacity(surface),
            outwardNormal,
            surface.materialFlags,
            surface.labPbrNormal,
            surface.labPbrSpecular,
            viewDirection,
            surface.t,
            volumeStack,
            surface.adjacentBaseColor,
            surface.adjacentSpecularControl);
    return primeEvaluateMinecraftTransmissionCompleteFromState(
            state,
            surface.baseColor,
            primeSurfaceOpacity(surface),
            surface.materialFlags,
            viewDirection,
            scatterDirection);
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
