#define PRIME_TWO_STAGE_WAVEFRONT 1
#define PRIME_STEADY_WAVEFRONT 1
#define PRIME_WAVEFRONT_LIGHT_EVENT_ONLY 1
#include "realtime/realtime_wavefront_bounce.slang"

static const uint PRIME_STEADY_EVENT_NONE = 0u;
static const uint PRIME_STEADY_EVENT_SUN = 1u;
static const uint PRIME_STEADY_EVENT_AREA = 2u;

uint primeSteadyEventQueue(uint eventType) {
    if (eventType == PRIME_STEADY_EVENT_SUN) {
        return PRIME_WAVEFRONT_AREA_QUEUE;
    }
    if (eventType == PRIME_STEADY_EVENT_AREA) {
        return PRIME_WAVEFRONT_TRACE_QUEUE_0;
    }
    return PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1;
}

void primeWavefrontSteadyClassify(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    SurfaceInteraction surface = primeFusedWavefrontTrace(
            context,
            recordIndex,
            primeWavefrontTransparentBranch(control));
    PrimeTransparentLightEvent lightEvent =
            primeClassifyTransparentLightEvent(
                    recordIndex, record, surface, control);
    uint eventType = PRIME_STEADY_EVENT_NONE;
    if (lightEvent.type == PRIME_TRANSPARENT_LIGHT_EVENT_SUN) {
        eventType = PRIME_STEADY_EVENT_SUN;
    } else if (lightEvent.type == PRIME_TRANSPARENT_LIGHT_EVENT_AREA) {
        eventType = PRIME_STEADY_EVENT_AREA;
    }
    primeStoreWavefrontSurface(recordIndex, surface);
    primeStoreWavefrontSunFamilyProbability(
            recordIndex, lightEvent.storedSunProbability);
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
    primeAppendWavefrontContinuation(
            PrimePostTraceWavefrontContinuationAppender(),
            primeSteadyEventQueue(eventType),
            recordIndex,
            true);
}

uint primeShadeSteadyEvent(
        inout PrimeIntegratorContext context,
        uint eventType,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint storedSunProbability) {
    PrimeTransparentLightEvent lightEvent;
    lightEvent.type = eventType == PRIME_STEADY_EVENT_SUN
            ? PRIME_TRANSPARENT_LIGHT_EVENT_SUN
            : eventType == PRIME_STEADY_EVENT_AREA
                    ? PRIME_TRANSPARENT_LIGHT_EVENT_AREA
                    : PRIME_TRANSPARENT_LIGHT_EVENT_NONE;
    lightEvent.storedSunProbability = storedSunProbability;
    if (eventType == PRIME_STEADY_EVENT_SUN) {
        PrimeNumericalDiagnostics shadeDiagnostics = context.diagnostics;
        PrimeFusedWavefrontLightResult light =
                primeShadeTransparentSunLightEvent(
                        context, recordIndex, record, surface, lightEvent);
        if (!light.diagnosticsCommitted) {
            context.diagnostics = shadeDiagnostics;
        }
        return light.storedSunProbability;
    }
    if (eventType != PRIME_STEADY_EVENT_AREA) {
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

void primeWavefrontSteadyConsume(
        inout PrimeIntegratorContext context,
        uint eventType,
        uint sourceQueue,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    uint storedSunProbability = primeShadeSteadyEvent(
            context,
            eventType,
            recordIndex,
            record,
            surface,
            primeLoadWavefrontSunFamilyProbability(recordIndex));
    bool continuation = primeWavefrontTransparentBranch(control)
            ? primeAdvanceTransparentWavefrontRecord(
                    context,
                    recordIndex,
                    record,
                    surface,
                    control,
                    storedSunProbability,
                    false)
            : primeAdvanceOrdinaryWavefrontRecord(
                    context,
                    recordIndex,
                    record,
                    surface,
                    control,
                    storedSunProbability,
                    false);
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0,
            recordIndex,
            continuation);
}
