#language slang 2026
module "transport/realtime/primary_chain.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "policy/realtime/queue.slang";
import "service/trace/context.slang";
import "transport/realtime/primary_chain_state.slang";
import "transport/realtime/primary_chain_guide.slang";
import "transport/realtime/trace.slang";
import "transport/scatter/delta.slang";

import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "state/realtime/transport.slang";
import "contract/path.slang";

public bool primePrimaryChainHitIsDeltaOnly(
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface) {
    if (surface.hitKind != PRIME_HIT_SURFACE) {
        return false;
    }
    PathState path = primeWavefrontPath(
            primeWavefrontPixel(recordIndex), record);
    return primeSurfaceIsDiscreteOnly(
            surface,
            -path.rayDirection,
            primeWavefrontVolumeStack(record));
}

public void primeQueuePrimaryChainLanding(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        SurfaceInteraction surface) {
    primeStoreWavefrontSurface(recordIndex, surface);
    primeAppendWavefrontContinuation(
            PrimePostTraceWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_PRIMARY_QUEUE,
            recordIndex,
            true);
}

public void primeProcessPrimaryChainBranch(
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
        SurfaceInteraction surface = primeFusedWavefrontTrace(
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
            bool guidePending = transparent
                    && primeWavefrontGuideEnabled(control)
                    && (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u
                    && !primeWavefrontDirectionalGuide(control)
                    && !primeWavefrontGuideProbeFallback(
                            record.sourceAndPathControl.w);
            if (guidePending) {
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
                        control,
                        transitions,
                        limit)
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

public void primeWavefrontPrimaryChain(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint taskIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_TRACE_QUEUE_0, launchIndex);
    uint2 pixel = primeWavefrontPixel(taskIndex);
    uint limit = primePrimaryChainLimit();
    PrimeWavefrontTransportRecord first =
            primeLoadWavefrontTransportRecord(primeWavefrontIndex(pixel, 0u));
    bool branchedPrimary = primeWavefrontTransparentBranch(
            asuint(first.rayDirectionAndDenoiserControl.w));
    uint branchCount = branchedPrimary ? 2u : 1u;
    [loop]
    for (uint branch = 0u; branch < branchCount; ++branch) {
        primeProcessPrimaryChainBranch(
                context, primeWavefrontIndex(pixel, branch), limit);
    }
}
