#ifndef PRIME_OFFLINE_BSDF_SLANG
#define PRIME_OFFLINE_BSDF_SLANG

// Offline transport uses the shared complete single-path closure. No event is split or forced, so
// the reference PDF includes reflection/transmission selection and remains directly usable by
// throughput and MIS.
PrimeTransmissiveBsdfSample primeSampleOfflineMinecraftTransmissionFromState(
        PrimeCompactTransmissionState state,
        float3 baseColor,
        float opacity,
        uint materialControl,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeNumericalObservation observation) {
    return primeSampleMinecraftTransmissionCompleteFromState(
            state,
            baseColor,
            opacity,
            materialControl,
            viewDirection,
            sampleValue,
            volumeStack,
            observation);
}

PrimeTransmissiveBsdfSample primeSampleOfflineMinecraftTransmissionFromState(
        PrimeCompactTransmissionState state,
        float3 baseColor,
        float opacity,
        uint materialControl,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeSampleOfflineMinecraftTransmissionFromState(
            state,
            baseColor,
            opacity,
            materialControl,
            viewDirection,
            sampleValue,
            volumeStack,
            observation);
}

BsdfEvaluation primeEvaluateOfflineMinecraftTransmission(
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 scatterDirection,
        PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeNumericalObservation observation) {
    float3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
    PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
            surface.baseColor,
            primeSurfaceOpacity(surface),
            outwardNormal,
            surface.materialControl,
            surface.roughness,
            surface.opticalControl,
            viewDirection,
            surface.t,
            volumeStack,
            surface.adjacentBaseColor,
            surface.adjacentInterfaceControl);
    return primeEvaluateMinecraftTransmissionCompleteFromState(
            state,
            surface.baseColor,
            primeSurfaceOpacity(surface),
            surface.materialControl,
            viewDirection,
            scatterDirection,
            observation);
}

BsdfEvaluation primeEvaluateOfflineMinecraftTransmission(
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 scatterDirection,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeNumericalObservation observation = primeNumericalObservationCreate(false);
    return primeEvaluateOfflineMinecraftTransmission(
            surface,
            viewDirection,
            scatterDirection,
            volumeStack,
            observation);
}

// Offline transport has one global guaranteed continuation. Interface type never grants an
// additional roulette exemption, including smooth discrete reflection and refraction chains.
bool primeOfflineSkipsRussianRoulette(BsdfSample bsdf) {
    return false;
}

#endif
