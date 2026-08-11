#ifndef PRIME_OFFLINE_OUTPUT_SLANG
#define PRIME_OFFLINE_OUTPUT_SLANG

import "core/auto_exposure.slang";

void primeWriteOfflineOutput(
        uint2 pixel,
        float2 cameraSample,
        PrimeOfflinePathRecord record) {
    float3 radiance = record.radianceAndPrimaryDistance.xyz;
    float primaryDistance = record.radianceAndPrimaryDistance.w;
    radiance = primeApplyAerialPerspective(
            pixel, cameraSample, primaryDistance, radiance);
    uint materialControl = asuint(
            record.primaryAlbedoAndMaterialFlags.w);
    float confidence = primeAutoExposureMaterialConfidence(
            uint(round(primeNrdMaterialId(materialControl) * 3.0)),
            primaryDistance);
    float meteredBrightness = primeAutoExposureMeteredBrightness(
            radiance,
            primeNrdSanitizeAlbedo(record.primaryAlbedoAndMaterialFlags.xyz),
            confidence);
    uint64_t zeroBasedSample = (uint64_t(primeSampleEpoch()) << 16u)
            | uint64_t(primeSampleIndex());
    float4 mean = float4(radiance, meteredBrightness);
    if (zeroBasedSample != uint64_t(0)) {
        float4 previous = primeImageLoad(primeOfflineRunningMean, int2(pixel));
        float sampleCount = float(zeroBasedSample + uint64_t(1));
        mean = previous + (mean - previous) / sampleCount;
    }
    primeImageStore(primeOfflineRunningMean, int2(pixel), mean);
}

#endif
