#ifndef PRIME_REALTIME_WAVEFRONT_TRACE_SLANG
#define PRIME_REALTIME_WAVEFRONT_TRACE_SLANG

#include "realtime/realtime_wavefront_common.slang"

SurfaceInteraction primeFusedWavefrontTrace(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        bool transparent) {
    uint packedDiagnostic = asuint(
            primeWavefrontPaths[recordIndex]
                    .transport.throughputAndNumericalFlags.w);
    context.diagnostics.flags =
            packedDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
    context.diagnostics.firstContext =
            (packedDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                    & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
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
    return primeTraceSurfaceClassified(
            PrimeSurfaceTracePolicy(),
            physicalOrigin,
            rayDirection,
            sourcePrimitive,
            pathClass);
}

#endif
