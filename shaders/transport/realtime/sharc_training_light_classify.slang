#language slang 2026
module "transport/realtime/sharc_training_light_classify.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "contract/path.slang";
import "contract/realtime/light_event.slang";
import "policy/realtime/queue.slang";
import "policy/runtime/config.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dispatch/traits.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/sharc_training.slang";
import "state/realtime/surface.slang";
import "bsdf/compact/contract/volume.slang";

public uint primeSharcTrainingEventQueue(uint eventType) {
    if (eventType == PRIME_REALTIME_LIGHT_EVENT_DUAL) {
        return PRIME_WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE;
    }
    return PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0;
}

public void primeClassifySharcTrainingLight(
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_PRIMARY_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint eventType = PRIME_REALTIME_LIGHT_EVENT_NONE;
    if (surface.hitKind == PRIME_HIT_SURFACE) {
        PrimeOpenPbrVolumeStack volumeStack =
                primeWavefrontVolumeStack(record);
        float3 viewDirection = -record.rayDirectionAndDenoiserControl.xyz;
        PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
                surface, viewDirection, volumeStack, primeUsesAirGap());
        if (primeClosureSupports(
                closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
            eventType = PRIME_REALTIME_LIGHT_EVENT_DUAL;
        }
    }
    primeAppendWavefrontContinuation(
            PrimeScalarWavefrontContinuationAppender(),
            primeSharcTrainingEventQueue(eventType),
            recordIndex,
            true);
}
