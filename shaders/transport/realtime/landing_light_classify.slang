#language slang 2026
module "transport/realtime/landing_light_classify.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "policy/realtime/queue.slang";
import "transport/realtime/light_event_classify.slang";
import "contract/realtime/light_event.slang";

import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";

public void primeClassifyLandingLight(uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_PRIMARY_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    PrimeRealtimeLightEvent lightEvent =
            primeClassifyRealtimeLightEvent(
                    recordIndex, record, surface, control);
    primeStoreWavefrontSunFamilyProbability(
            recordIndex, lightEvent.storedSunProbability);

    // GUIDE_DUAL owns the dense guide light event. Rare terminal states stay with the
    // sampled-light family instead of creating a mostly empty NONE dispatch.
    uint queue = lightEvent.type == PRIME_REALTIME_LIGHT_EVENT_GUIDE_DUAL
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
