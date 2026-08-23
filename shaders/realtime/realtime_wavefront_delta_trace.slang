#ifndef PRIME_REALTIME_WAVEFRONT_DELTA_TRACE_SLANG
#define PRIME_REALTIME_WAVEFRONT_DELTA_TRACE_SLANG

#include "realtime/realtime_wavefront_delta_common.slang"

SurfaceInteraction primeFusedDeltaWavefrontTrace(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        bool transparent) {
    uint bounce = primeWavefrontPaths[recordIndex]
                    .transport.sourceAndPathControl.w
            & PRIME_WAVEFRONT_BYTE_MASK;
    float3 physicalOrigin = primeWavefrontPaths[recordIndex]
            .transport.physicalOriginAndPreviousBsdfPdf.xyz;
    uint2 sourcePrimitive = primeWavefrontPaths[recordIndex]
            .transport.sourceAndPathControl.xy;
    float3 rayDirection = primeWavefrontPaths[recordIndex]
            .transport.rayDirectionAndDenoiserControl.xyz;
    uint traceControl = asuint(
            primeWavefrontPaths[recordIndex]
                    .transport.rayDirectionAndDenoiserControl.w);
    uint pathClass = !transparent
            ? 0u
            : primeWavefrontTransmissionBranch(traceControl) ? 2u : 1u;
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_TRACE, bounce);
    SurfaceInteraction surface = primeTraceSurfaceClassified(
            PrimeSurfaceTracePolicy(),
            physicalOrigin,
            rayDirection,
            sourcePrimitive,
            pathClass);
    return surface;
}

#endif
