#include "realtime/realtime_wavefront_common.slang"

#include "realtime/transparent_guide_probe.slang"
#include "realtime/wavefront_output.slang"

void primeWavefrontTransparentResolve(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE, launchIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeWavefrontTransportRecord primaryRecord =
            primeWavefrontPaths[primeWavefrontIndex(pixel, 0u)].transport;
    uint primaryDiagnostic =
            asuint(primaryRecord.throughputAndNumericalFlags.w);
    context.diagnostics.flags =
            primaryDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
    context.diagnostics.firstContext =
            (primaryDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                    & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
    uint secondaryDiagnostic = asuint(
            primeWavefrontPaths[primeWavefrontIndex(pixel, 1u)]
                    .transport.throughputAndNumericalFlags.w);
    uint secondaryFlags =
            secondaryDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
    if (context.diagnostics.flags == 0u && secondaryFlags != 0u) {
        context.diagnostics.firstContext =
                (secondaryDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                        & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
    }
    context.diagnostics.flags |= secondaryFlags;

    float2 cameraSample = primeRealtimeCameraSample(pixel);
    PrimeTransparentGuideProbeResult probes =
            primeEmptyTransparentGuideProbeResult();
    bool hasProbes = false;
    if (primeTransparentGuideMode()
            != PRIME_PATH_TRANSPARENT_GUIDE_MODE_DISABLED) {
        PathState cameraPath = primeCameraPath(pixel, 0u, cameraSample);
        SurfaceInteraction primarySurface = primeTraceSurfaceWithoutReorder(
                primePush.cameraPosition, cameraPath.rayDirection);
        if (primarySurface.hitKind == PRIME_HIT_SURFACE
                && primeMaterialIsTransmissive(primarySurface.materialControl)) {
            PrimeOpenPbrVolumeStack volumeStack =
                    (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
                    ? primeCameraWaterVolumeStack()
                    : primeEmptyVolumeStack();
            probes = primeTraceTransparentGuideProbes(context,
                    cameraPath,
                    primarySurface,
                    volumeStack,
                    primeMaximumBounces());
            hasProbes = true;
        }
    }

    // Resolve after every probe trace so the complete radiance and guide result does not remain
    // live across traversal. The probes are reconstruction-only and cannot affect this load.
    PrimeIntegrationResult result =
            primeResolveTransparentWavefrontResult(pixel);
    float4 reflectionPreviousVirtualPosition = float4(0.0, 0.0, 0.0, -1.0);
    if (hasProbes) {
        result.transmissionGuides = primeGuideWithProbeSurface(
                result.transmissionGuides, probes.transmission);
        if (probes.transmission.primaryHitKind == PRIME_HIT_SURFACE) {
            result.transmissionAnchorDistance =
                    probes.transmissionAnchorDistance;
        }
        result.reflectionGuides = primeGuideWithProbeSurface(
                result.reflectionGuides, probes.reflection);
        if (probes.reflection.primaryHitKind == PRIME_HIT_SURFACE) {
            result.reflectionDirectionalGuide = false;
        }
        reflectionPreviousVirtualPosition =
                probes.reflectionPreviousVirtualPosition;
    }
    primeWriteRealtimeOutput(context.diagnostics,
            pixel,
            cameraSample,
            result,
            reflectionPreviousVirtualPosition);
}
