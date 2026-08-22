#define PRIME_TWO_STAGE_WAVEFRONT 1
#include "realtime/realtime_wavefront_bounce.slang"
#include "realtime/realtime_wavefront_primary_landing_publish.slang"

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
                        PrimeNoWavefrontSharcTraining(),
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
                    PrimeNoWavefrontSharcTraining(),
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
