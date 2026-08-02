#ifndef PRIME_OFFLINE_OUTPUT_GLSL
#define PRIME_OFFLINE_OUTPUT_GLSL

#include "auto_exposure.glsl"

void primeWriteOfflineOutput(
        uvec2 pixel,
        vec2 cameraSample,
        PrimeOfflinePathRecord record) {
    vec3 radiance = record.radianceAndPrimaryDistance.xyz;
    float primaryDistance = record.radianceAndPrimaryDistance.w;
    primeApplyAerialPerspective(pixel, cameraSample, primaryDistance, radiance);
    uint materialFlags = floatBitsToUint(
            record.primaryAlbedoAndMaterialFlags.w);
    float confidence = primeAutoExposureMaterialConfidence(
            uint(round(primeNrdMaterialId(materialFlags) * 3.0)),
            primaryDistance);
    float meteredLuminance = primeAutoExposureMeteredLuminance(
            radiance,
            primeNrdSanitizeAlbedo(record.primaryAlbedoAndMaterialFlags.xyz),
            confidence);
    uint64_t zeroBasedSample = (uint64_t(primeSampleEpoch()) << 16u)
            | uint64_t(primeSampleIndex());
    vec4 mean = vec4(radiance, meteredLuminance);
    if (zeroBasedSample != uint64_t(0)) {
        vec4 previous = imageLoad(primeOfflineRunningMean, ivec2(pixel));
        float sampleCount = float(zeroBasedSample + uint64_t(1));
        mean = previous + (mean - previous) / sampleCount;
    }
    imageStore(primeOfflineRunningMean, ivec2(pixel), mean);
}

#endif
