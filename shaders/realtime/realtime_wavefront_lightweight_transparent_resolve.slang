#include "realtime/realtime_wavefront_common.slang"

#include "realtime/wavefront_output.slang"

void primeWavefrontLightweightTransparentResolve(
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

    PrimeIntegrationResult result =
            primeResolveTransparentWavefrontResult(pixel);
    if (result.transparentReflectionOnly) {
        result.transmissionGuides = primeEmptyDenoiserGuides();
        result.transmissionAnchorDistance = -1.0;
    }
    primeWriteRealtimeOutput(
            context.diagnostics,
            pixel,
            primeRealtimeCameraSample(pixel),
            result);
}
