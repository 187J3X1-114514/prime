#include "realtime/realtime_wavefront_common.slang"

void primeWavefrontHead(
        inout PrimeIntegratorContext context, uint2 launchIndex) {
    uint2 pixel = launchIndex;
    primeSetNumericalContext(context.diagnostics, PRIME_NUMERICAL_STAGE_CAMERA, 0u);
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    uint maximumBounces = primeMaximumBounces();
    bool continuation = maximumBounces != 0u;
    uint pathIndex = primeWavefrontIndex(pixel, 0u);
    uint primaryHitKind = PRIME_HIT_NONE;
    if (continuation) {
        float3 traceDirection = primeCameraRayDirection(pixel, cameraSample);
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_TRACE, 0u);
        SurfaceInteraction surface = primeTraceSurfaceClassified(
                PrimeSurfaceTracePolicy(),
                primePush.cameraPosition,
                traceDirection,
                0u);
        primaryHitKind = surface.hitKind;
        primeStoreWavefrontSurface(pathIndex, surface);
    }

    // Build large transport records after SER so they do not enter the reorder spill set.
    PathState path = primeCameraPath(pixel, 0u, cameraSample);
    PrimeOpenPbrVolumeStack volumeStack =
            (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
    PrimeDenoiserState denoiserState = primeInitialDenoiserState();
    PrimeIntegrationResult result = primeEmptyIntegrationResult();
    PrimeWavefrontPathRecord record = primeMakeWavefrontRecord(
            context.diagnostics,
            path,
            volumeStack,
            denoiserState,
            result.guides,
            continuation);
    primeWavefrontPaths[pathIndex] = record;
    record.transport = primeWavefrontWithActive(record.transport, false);
    primeWavefrontPaths[primeWavefrontIndex(pixel, 1u)] = record;
    primeStoreWavefrontArea(pixel, result.guides);
    primeStoreWavefrontIntermediate(pixel, result);
    // SER may repack invocations. Queue publication after it must not use subgroup collectives.
    if (continuation) {
        primeAppendWavefrontPath(PRIME_WAVEFRONT_SHADE_QUEUE, pathIndex);
        if (primaryHitKind == PRIME_HIT_SURFACE) {
            primeAppendWavefrontPath(PRIME_WAVEFRONT_AREA_QUEUE, pathIndex);
        }
    }
}
