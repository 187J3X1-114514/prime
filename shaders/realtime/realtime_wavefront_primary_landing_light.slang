#define PRIME_TWO_STAGE_WAVEFRONT 1
#define PRIME_WAVEFRONT_LIGHT_EVENT_ONLY 1
#define PRIME_WAVEFRONT_NO_ADVANCE 1
#include "realtime/realtime_wavefront_bounce.slang"

uint primeShadePrimaryLandingPrimary(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface) {
    PrimeTransparentLightEvent lightEvent;
    lightEvent.type = PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY;
    lightEvent.storedSunProbability = 0u;
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

void primeWavefrontPrimaryLandingLight(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_TRACE_QUEUE_0, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint storedSunProbability = primeShadePrimaryLandingPrimary(
            context, recordIndex, record, surface);
    primeStoreWavefrontSunFamilyProbability(
            recordIndex, storedSunProbability);
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
    // Shadow traversal may reorder SER invocations, so reserve the bridge queue scalarly.
    primeAppendWavefrontContinuation(
            PrimePostTraceWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_AREA_QUEUE,
            recordIndex,
            true);
}
