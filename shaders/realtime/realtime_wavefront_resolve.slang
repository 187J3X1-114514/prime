#include "realtime/realtime_wavefront_common.slang"

#include "realtime/wavefront_output.slang"

void primeWavefrontResolve(
        inout PrimeIntegratorContext context, uint2 launchIndex) {
    uint2 pixel = launchIndex;
    if (primeWavefrontPixelTransparent(pixel)) {
        return;
    }
    PrimeWavefrontTransportRecord primaryRecord =
            primeWavefrontPaths[primeWavefrontIndex(pixel, 0u)].transport;
    uint primaryDiagnostic =
            asuint(primaryRecord.throughputAndNumericalFlags.w);
    context.diagnostics.flags =
            primaryDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
    context.diagnostics.firstContext =
            (primaryDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                    & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
    PrimeIntegrationResult result = primeLoadWavefrontIntermediate(
            pixel, primaryRecord);
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    primeWriteRealtimeOutput(context.diagnostics, pixel, cameraSample, result);
}
