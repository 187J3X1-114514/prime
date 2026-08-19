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
    PrimeWavefrontTransportRecord secondaryRecord =
            primeWavefrontPaths[primeWavefrontIndex(pixel, 1u)].transport;
    uint primaryPathControl = primaryRecord.sourceAndPathControl.w;
    uint secondaryPathControl = secondaryRecord.sourceAndPathControl.w;
    uint reflectionControl = asuint(
            secondaryRecord.rayDirectionAndDenoiserControl.w);
    uint primaryDiagnostic =
            asuint(primaryRecord.throughputAndNumericalFlags.w);
    context.diagnostics.flags =
            primaryDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
    context.diagnostics.firstContext =
            (primaryDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                    & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
    uint secondaryDiagnostic = asuint(
            secondaryRecord.throughputAndNumericalFlags.w);
    uint secondaryFlags =
            secondaryDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
    if (context.diagnostics.flags == 0u && secondaryFlags != 0u) {
        context.diagnostics.firstContext =
                (secondaryDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                        & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
    }
    context.diagnostics.flags |= secondaryFlags;

    float2 cameraSample = primeRealtimeCameraSample(pixel);
    bool guideModeEnabled = primeTransparentGuideMode()
            != PRIME_PATH_TRANSPARENT_GUIDE_MODE_DISABLED;
    uint probeMask = 0u;
    if (primeWavefrontGuideProbeFallback(primaryPathControl)) {
        probeMask |= PRIME_GUIDE_PROBE_TRANSMISSION_MASK;
    }
    if (primeWavefrontGuideProbeFallback(secondaryPathControl)) {
        probeMask |= PRIME_GUIDE_PROBE_REFLECTION_MASK;
    }
    PrimeTransparentGuideProbeResult probes =
            primeEmptyTransparentGuideProbeResult();
    bool hasProbes = false;
    if (probeMask != 0u && guideModeEnabled) {
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
                    primeMaximumBounces(),
                    probeMask);
            hasProbes = true;
        }
    }

    // Resolve after every probe trace so the complete radiance and guide result does not remain
    // live across traversal. The probes are reconstruction-only and cannot affect this load.
    PrimeIntegrationResult result =
            primeResolveTransparentWavefrontResult(pixel);
    float4 reflectionPreviousVirtualPosition =
            (probeMask & PRIME_GUIDE_PROBE_REFLECTION_MASK) == 0u
            ? primeCapturedReflectionPreviousVirtualPosition(
                    result,
                    (reflectionControl & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE)
                            != 0u)
            : float4(0.0, 0.0, 0.0, -1.0);
    if (!guideModeEnabled) {
        // Full mode historically ignored its queued TIR marker when transparent reconstruction was
        // disabled; keep that output topology independent of this probe-elision path.
        result.transparentReflectionOnly = false;
    }
    if (result.transparentReflectionOnly) {
        // The transmission identity does not exist under TIR. Keeping the visible glass albedo in
        // this lane asks the denoiser to reconstruct a signal that is exactly zero.
        result.transmissionGuides = primeEmptyDenoiserGuides();
        result.transmissionAnchorDistance = -1.0;
    }
    if (hasProbes) {
        if ((probeMask & PRIME_GUIDE_PROBE_TRANSMISSION_MASK) != 0u
                && !result.transparentReflectionOnly) {
            result.transmissionGuides = primeGuideWithProbeSurface(
                    result.transmissionGuides, probes.transmission);
            if (probes.transmission.primaryHitKind == PRIME_HIT_SURFACE) {
                result.transmissionAnchorDistance =
                        probes.transmissionAnchorDistance;
            }
        }
        if ((probeMask & PRIME_GUIDE_PROBE_REFLECTION_MASK) != 0u) {
            result.reflectionGuides = primeGuideWithProbeSurface(
                    result.reflectionGuides, probes.reflection);
            if (probes.reflectionCurrentVirtualPosition.w >= 0.0) {
                result.reflectionGuides.primaryPosition =
                        probes.reflectionCurrentVirtualPosition.xyz;
                result.reflectionGuides.primaryPreviousPosition =
                        probes.reflectionPreviousVirtualPosition.xyz;
                result.reflectionGuides.primaryHasMotion =
                        probes.reflectionHasMotion;
                result.reflectionDirectionalGuide =
                        probes.reflectionDirectionalGuide;
            }
            reflectionPreviousVirtualPosition =
                    probes.reflectionPreviousVirtualPosition;
        }
    }
    primeWriteRealtimeOutput(context.diagnostics,
            pixel,
            cameraSample,
            result,
            reflectionPreviousVirtualPosition);
}
