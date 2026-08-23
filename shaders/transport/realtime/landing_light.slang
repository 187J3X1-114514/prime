#language slang 2026
module "transport/realtime/landing_light.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "transport/realtime/light_event_shade.slang";
import "transport/realtime/light_event_classify.slang";
import "contract/realtime/light_event.slang";

import "service/light/sample.slang";
import "policy/realtime/sharc.slang";
import "state/realtime/surface.slang";
import "policy/realtime/queue.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";

public uint primeShadePrimaryLandingPrimary(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface) {
    PrimeTransparentLightEvent lightEvent;
    lightEvent.type = PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY;
    lightEvent.storedSunProbability = 0u;
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
                    PrimeNoWavefrontSharcTraining(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    lightEvent,
                    preparedArea,
                    preparedThroughput,
                    area);
    return light.storedSunProbability;
}

public void primeWavefrontPrimaryLandingLight(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_TRACE_QUEUE_0, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint storedSunProbability = primeShadePrimaryLandingPrimary(
            context, recordIndex, record, surface);
    primeStoreWavefrontSunFamilyProbability(
            recordIndex, storedSunProbability);
    // Shadow traversal may reorder SER invocations, so reserve the bridge queue scalarly.
    primeAppendWavefrontContinuation(
            PrimePostTraceWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_AREA_QUEUE,
            recordIndex,
            true);
}
