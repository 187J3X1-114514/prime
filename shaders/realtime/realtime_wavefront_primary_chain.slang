#define PRIME_TWO_STAGE_WAVEFRONT 1
#include "realtime/realtime_wavefront_bounce.slang"

uint primeShadePrimaryChainVertex(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        PrimeTransparentLightEvent lightEvent) {
    if (lightEvent.type == PRIME_TRANSPARENT_LIGHT_EVENT_SUN) {
        PrimeNumericalDiagnostics shadeDiagnostics = context.diagnostics;
        PrimeFusedWavefrontLightResult light =
                primeShadeTransparentSunLightEvent(
                        context, recordIndex, record, surface, lightEvent);
        if (!light.diagnosticsCommitted) {
            context.diagnostics = shadeDiagnostics;
        }
        return light.storedSunProbability;
    }
    if (!primeTransparentLightEventSamplesArea(lightEvent)) {
        return 0u;
    }

    PrimeNumericalDiagnostics shadeDiagnostics = context.diagnostics;
    PathState path = primeWavefrontPath(
            primeWavefrontPixel(recordIndex), record);
    float3 preparedThroughput = float3(0.0);
    PrimeTransparentAreaSampleInput lightData = {};
    bool preparedArea = primePrepareTransparentAreaSample(
            context,
            path,
            primeWavefrontVolumeStack(record),
            surface,
            lightEvent,
            preparedThroughput,
            lightData);
    AreaLightSample area = {};
    if (preparedArea) {
        area = primeExecuteTransparentAreaSample(context, lightData);
    }
    PrimeFusedWavefrontLightResult light =
            primeShadeTransparentAreaLightEvent(
                    context,
                    recordIndex,
                    record,
                    surface,
                    lightEvent,
                    preparedArea,
                    preparedThroughput,
                    area);
    if (!light.diagnosticsCommitted) {
        context.diagnostics = shadeDiagnostics;
    }
    return light.storedSunProbability;
}

void primeInvalidatePrimaryChainGuide(
        uint recordIndex,
        PrimeWavefrontTransportRecord record) {
    uint2 pixel = primeWavefrontPixel(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    bool transmission = primeWavefrontTransmissionBranch(control);
    PrimeTransparentBranchResult result =
            primeLoadTransparentBranchActiveIntermediate(
                    pixel, transmission, control);
    result.guides = primeEmptyDenoiserGuides();
    result.anchorDistance = -1.0;
    result.directionalGuide = false;
    primeStoreTransparentBranchIntermediate(
            pixel,
            transmission,
            false,
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            result);
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

    // The primary transmissive sample has already performed transition one.
    uint transitions = 1u;
    [loop]
    for (uint step = 0u; step < limit; ++step) {
        SurfaceInteraction surface = primeFusedWavefrontTrace(
                context,
                recordIndex,
                primeWavefrontTransparentBranch(
                        asuint(record.rayDirectionAndDenoiserControl.w)));
        record = primeLoadWavefrontTransportRecord(recordIndex);
        uint control = asuint(record.rayDirectionAndDenoiserControl.w);
        PrimeTransparentLightEvent lightEvent =
                primeClassifyTransparentLightEvent(
                        recordIndex, record, surface, control);
        uint storedSunProbability = primeShadePrimaryChainVertex(
                context, recordIndex, record, surface, lightEvent);
        bool transparent = primeWavefrontTransparentBranch(control);
        bool continuation = transparent
                ? primeAdvanceTransparentWavefrontRecord(
                        context,
                        recordIndex,
                        record,
                        surface,
                        control,
                        storedSunProbability,
                        true)
                : primeAdvanceOrdinaryWavefrontRecord(
                        context,
                        recordIndex,
                        record,
                        surface,
                        control,
                        storedSunProbability,
                        true);
        if (!continuation) {
            return;
        }

        record = primeLoadWavefrontTransportRecord(recordIndex);
        PathState updatedPath = primeWavefrontPath(
                primeWavefrontPixel(recordIndex), record);
        bool delta =
                (updatedPath.flags & PRIME_PATH_PREVIOUS_DISCRETE) != 0u;
        if (!delta) {
            uint psrControl = (record.sourceAndPathControl.w
                    >> PRIME_WAVEFRONT_PSR_CONTROL_SHIFT)
                    & PRIME_WAVEFRONT_PSR_CONTROL_MASK;
            PrimeQueuedPsrState psrState = primeEmptyQueuedPsrState();
            psrState.control = psrControl;
            bool invalidGuide =
                    primeQueuedPsrOverflowed(psrState)
                    || primeWavefrontGuideProbeFallback(
                            record.sourceAndPathControl.w);
            if (transparent && invalidGuide) {
                primeInvalidatePrimaryChainGuide(recordIndex, record);
            }
            record = primeWavefrontSteadyState(record);
            primeStoreWavefrontTransportRecord(recordIndex, record);
            primeAppendWavefrontContinuation(
                    PrimePostTraceWavefrontContinuationAppender(),
                    PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0,
                    recordIndex,
                    true);
            return;
        }

        if (transitions >= limit) {
            if (transparent) {
                primeInvalidatePrimaryChainGuide(recordIndex, record);
            }
            record = primeWavefrontWithActive(record, false);
            record = primeWavefrontWithGuideProbeFallback(record, false);
            primeStoreWavefrontTransportRecord(recordIndex, record);
            return;
        }
        transitions++;
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
