#define PRIME_TWO_STAGE_WAVEFRONT 1
#define PRIME_WAVEFRONT_LIGHT_EVENT_ONLY 1
#define PRIME_WAVEFRONT_CLASSIFY_ONLY 1
#include "realtime/realtime_wavefront_bounce.slang"

void primeWavefrontPrimaryLandingClassify(uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_PRIMARY_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    PrimeTransparentLightEvent lightEvent =
            primeClassifyTransparentLightEvent(
                    recordIndex, record, surface, control);
    primeStoreWavefrontSunFamilyProbability(
            recordIndex, lightEvent.storedSunProbability);

    // PRIMARY owns the dense guide/dual-light bridge. Rare terminal states stay with the
    // secondary family instead of creating a mostly empty NONE dispatch.
    uint queue = lightEvent.type == PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY
            ? PRIME_WAVEFRONT_TRACE_QUEUE_0
            : PRIME_WAVEFRONT_AREA_QUEUE;
    // The event partition is lane-varying. A subgroup reservation would reserve only the
    // elected lane's queue, then reuse its offset while other lanes write different queues.
    primeAppendWavefrontContinuation(
            PrimeScalarWavefrontContinuationAppender(),
            queue,
            recordIndex,
            true);
}
