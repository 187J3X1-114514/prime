#define PRIME_TWO_STAGE_WAVEFRONT 1
#include "realtime/realtime_wavefront_bounce.slang"
#include "realtime/realtime_wavefront_primary_chain_guide.slang"

uint primeShadePrimaryLanding(
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

void primeWavefrontPrimaryLanding(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_PRIMARY_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    PrimeTransparentLightEvent lightEvent =
            primeClassifyTransparentLightEvent(
                    recordIndex, record, surface, control);
    uint storedSunProbability = primeShadePrimaryLanding(
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
    uint psrControl = (record.sourceAndPathControl.w
            >> PRIME_WAVEFRONT_PSR_CONTROL_SHIFT)
            & PRIME_WAVEFRONT_PSR_CONTROL_MASK;
    PrimeQueuedPsrState psrState = primeEmptyQueuedPsrState();
    psrState.control = psrControl;
    bool invalidGuide = primeQueuedPsrOverflowed(psrState)
            || primeWavefrontGuideProbeFallback(record.sourceAndPathControl.w);
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
}
