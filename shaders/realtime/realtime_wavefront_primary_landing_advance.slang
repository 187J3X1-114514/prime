#define PRIME_TWO_STAGE_WAVEFRONT 1
#include "realtime/realtime_wavefront_advance.slang"
#include "realtime/realtime_wavefront_primary_landing_publish.slang"

void primeWavefrontPrimaryLandingAdvance(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    uint storedSunProbability =
            primeLoadWavefrontSunFamilyProbability(recordIndex);
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
