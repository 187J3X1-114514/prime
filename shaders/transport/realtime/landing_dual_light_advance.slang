#language slang 2026
module "transport/realtime/landing_dual_light_advance.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "contract/path.slang";
import "policy/realtime/landing.slang";
import "state/realtime/surface.slang";
import "state/realtime/queue.slang";
import "service/light/sample.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";

import "transport/realtime/light_event_shade.slang";
import "transport/realtime/light_event_classify.slang";
import "contract/realtime/light_event.slang";
import "transport/realtime/advance.slang";
import "transport/realtime/landing_publish.slang";

public void primeShadeLandingDualLight(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface) {
    if (surface.hitKind != PRIME_HIT_SURFACE) {
        return;
    }
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    PrimeRealtimeLightEvent lightEvent = primeClassifyRealtimeLightEvent(
            recordIndex, record, surface, control);
    if (lightEvent.type != PRIME_REALTIME_LIGHT_EVENT_DUAL) {
        return;
    }
    PathState path = primeWavefrontPath(
            primeWavefrontPixel(recordIndex), record);

    float3 preparedThroughput = float3(0.0);
    PrimeRealtimeAreaSampleInput lightData = {};
    bool preparedArea = primePrepareRealtimeAreaSample(
            context,
            path,
            primeWavefrontVolumeStack(record),
            surface,
            lightEvent,
            preparedThroughput,
            lightData);
    AreaLightSample area = {};
    if (preparedArea) {
        area = primeExecuteRealtimeAreaSample(context, lightData);
    }
    primeShadeRealtimeDualLightEvent(
            context,
            recordIndex,
            record,
            surface,
            lightEvent,
            preparedArea,
            preparedThroughput,
            area);
}

public void primeShadeAndAdvanceLandingDualLight(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    primeShadeLandingDualLight(
            context, recordIndex, record, surface);
    bool transparent = primeWavefrontTransparentBranch(control);
    bool continuation = transparent
            ? primeAdvanceTransparentWavefrontRecord(
                    PrimeLandingTransportPhase(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control,
                    true)
            : primeAdvanceOrdinaryWavefrontRecord(
                    PrimeLandingTransportPhase(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control,
                    true);
    primePublishLandingContinuation(recordIndex, transparent, continuation);
}
