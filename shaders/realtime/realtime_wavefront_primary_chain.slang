#define PRIME_TWO_STAGE_WAVEFRONT 1
#include "realtime/realtime_wavefront_primary_chain_state.slang"

bool primePrimaryChainHitIsDeltaOnly(
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface) {
    if (surface.hitKind != PRIME_HIT_SURFACE) {
        return false;
    }
    PathState path = primeWavefrontPath(
            primeWavefrontPixel(recordIndex), record);
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface,
            -path.rayDirection,
            primeWavefrontVolumeStack(record));
    return primeClosureIsDiscreteOnly(closureTraits);
}

void primeQueuePrimaryChainLanding(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        SurfaceInteraction surface) {
    primeStoreWavefrontSurface(recordIndex, surface);
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
    primeAppendWavefrontContinuation(
            PrimePostTraceWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_PRIMARY_QUEUE,
            recordIndex,
            true);
}

void primeProcessPrimaryChainBranch(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        uint limit) {
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    if (!primeWavefrontActive(record)) {
        return;
    }

    // PRIMARY already completed the first delta transition. Each iteration either lands without
    // sampling, or performs exactly one additional delta transition.
    uint transitions = 1u;
    [loop]
    for (uint step = 0u; step < limit; ++step) {
        uint control = asuint(record.rayDirectionAndDenoiserControl.w);
        bool transparent = primeWavefrontTransparentBranch(control);
        SurfaceInteraction surface = primeFusedDeltaWavefrontTrace(
                context, recordIndex, transparent);
        record = primeLoadWavefrontTransportRecord(recordIndex);
        control = asuint(record.rayDirectionAndDenoiserControl.w);

        if (!primePrimaryChainHitIsDeltaOnly(
                recordIndex, record, surface)) {
            primeQueuePrimaryChainLanding(context, recordIndex, surface);
            return;
        }

        // Limit one permits the transition already completed by PRIMARY and its landing trace.
        if (transitions >= limit) {
            if (transparent) {
                primeInvalidatePrimaryChainGuide(recordIndex, record);
            }
            record = primeWavefrontWithActive(record, false);
            record = primeWavefrontWithGuideProbeFallback(record, false);
            primeStoreWavefrontTransportRecord(recordIndex, record);
            return;
        }

        bool continuation = transparent
                ? primeAdvanceTransparentPrimaryDelta(
                        context,
                        recordIndex,
                        record,
                        surface,
                        control)
                : primeAdvanceOrdinaryPrimaryDelta(
                        context,
                        recordIndex,
                        record,
                        surface,
                        control);
        if (!continuation) {
            return;
        }
        transitions++;
        record = primeLoadWavefrontTransportRecord(recordIndex);
    }
}

void primeWavefrontPrimaryChain(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint taskIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_TRACE_QUEUE_0, launchIndex);
    uint2 pixel = primeWavefrontPixel(taskIndex);
    uint limit = primePrimaryChainLimit();
    PrimeWavefrontTransportRecord first =
            primeLoadWavefrontTransportRecord(primeWavefrontIndex(pixel, 0u));
    bool branchedPrimary = primeWavefrontTransparentBranch(
            asuint(first.rayDirectionAndDenoiserControl.w));
    primeProcessPrimaryChainBranch(
            context, primeWavefrontIndex(pixel, 0u), limit);
    if (branchedPrimary) {
        primeProcessPrimaryChainBranch(
                context, primeWavefrontIndex(pixel, 1u), limit);
    }
}
