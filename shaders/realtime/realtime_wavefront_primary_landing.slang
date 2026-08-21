#define PRIME_TWO_STAGE_WAVEFRONT 1
#define PRIME_WAVEFRONT_LIGHT_EVENT_ONLY 1
#include "realtime/realtime_wavefront_bounce.slang"
#include "realtime/realtime_wavefront_primary_chain_guide.slang"

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

uint primeShadePrimaryLandingSecondary(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface) {
    uint storedSunProbability =
            primeLoadWavefrontSunFamilyProbability(recordIndex);
    if (!primeHasStoredLightFamilyProbability(storedSunProbability)) {
        return 0u;
    }

    PathState path = primeWavefrontPath(
            primeWavefrontPixel(recordIndex), record);
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    float sunProbability = primeSunFamilyProbabilityFromStored(
            storedSunProbability);
    PrimeTransparentLightEvent lightEvent;
    lightEvent.type = primeSecondarySamplesSunLight(
            preparedSample, sunProbability)
            ? PRIME_TRANSPARENT_LIGHT_EVENT_SUN
            : PRIME_TRANSPARENT_LIGHT_EVENT_AREA;
    lightEvent.storedSunProbability = storedSunProbability;
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

    PrimeNumericalDiagnostics shadeDiagnostics = context.diagnostics;
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

void primePublishPrimaryLanding(
        uint recordIndex, bool transparent, bool continuation) {
    if (continuation) {
        PrimeWavefrontTransportRecord record =
                primeLoadWavefrontTransportRecord(recordIndex);
        uint psrControl = (record.sourceAndPathControl.w
                >> PRIME_WAVEFRONT_PSR_CONTROL_SHIFT)
                & PRIME_WAVEFRONT_PSR_CONTROL_MASK;
        PrimeQueuedPsrState psrState = primeEmptyQueuedPsrState();
        psrState.control = psrControl;
        bool invalidGuide = primeQueuedPsrOverflowed(psrState)
                || primeWavefrontGuideProbeFallback(
                        record.sourceAndPathControl.w);
        if (transparent && invalidGuide) {
            primeInvalidatePrimaryChainGuide(recordIndex, record);
        }
        record = primeWavefrontSteadyState(record);
        primeStoreWavefrontTransportRecord(recordIndex, record);
    }
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0,
            recordIndex,
            continuation);
}

void primeWavefrontPrimaryLandingPrimary(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_TRACE_QUEUE_0, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    uint storedSunProbability = primeShadePrimaryLandingPrimary(
            context, recordIndex, record, surface);
    bool continuation = primeAdvanceTransparentWavefrontRecord(
            context,
            recordIndex,
            record,
            surface,
            control,
            storedSunProbability,
            true);
    primePublishPrimaryLanding(recordIndex, true, continuation);
}

void primeWavefrontPrimaryLandingSecondary(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    uint storedSunProbability = primeShadePrimaryLandingSecondary(
            context, recordIndex, record, surface);
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
    primePublishPrimaryLanding(recordIndex, transparent, continuation);
}
