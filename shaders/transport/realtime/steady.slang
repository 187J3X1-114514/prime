#language slang 2026
module "transport/realtime/steady.slang";

import "policy/realtime/steady.slang";
import "policy/realtime/queue.slang";
import "prime_abi.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
import "service/light/sample.slang";
import "service/trace/context.slang";
import "state/realtime/guide.slang";
import "transport/surface/emission.slang";
import "transport/surface/medium.slang";
import "policy/queue/wavefront.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "state/realtime/transparent.slang";
import "state/realtime/transport.slang";

import "transport/realtime/light_event_shade.slang";
import "transport/realtime/light_event_classify.slang";
import "transport/realtime/trace.slang";
import "contract/realtime/light_event.slang";
import "transport/realtime/advance.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";

public static const uint PRIME_STEADY_EVENT_NONE = 0u;
public static const uint PRIME_STEADY_EVENT_DUAL = 1u;

public uint primeSteadyEventQueue(uint eventType) {
    if (eventType == PRIME_STEADY_EVENT_DUAL) {
        return PRIME_WAVEFRONT_TRACE_QUEUE_0;
    }
    return PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1;
}

public void primeQueueSteadyEvent(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface) {
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    PrimeRealtimeLightEvent lightEvent =
            primeClassifyRealtimeLightEvent(
                    recordIndex, record, surface, control);
    uint eventType = PRIME_STEADY_EVENT_NONE;
    if (lightEvent.type == PRIME_REALTIME_LIGHT_EVENT_DUAL) {
        eventType = PRIME_STEADY_EVENT_DUAL;
    }
    primeStoreWavefrontSurface(recordIndex, surface);
    // NONE and DUAL are a per-lane partition, so the queue is not subgroup-uniform.
    primeAppendWavefrontContinuation(
            PrimeScalarWavefrontContinuationAppender(),
            primeSteadyEventQueue(eventType),
            recordIndex,
            true);
}

public void primeWavefrontSteadyClassify(
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
    primeQueueSteadyEvent(context, recordIndex, record, surface);
}

public void primeShadeSteadyEvent(
        inout PrimeIntegratorContext context,
        uint eventType,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface) {
    PrimeRealtimeLightEvent lightEvent;
    lightEvent.type = eventType == PRIME_STEADY_EVENT_DUAL
            ? PRIME_REALTIME_LIGHT_EVENT_DUAL
            : PRIME_REALTIME_LIGHT_EVENT_NONE;
    if (eventType != PRIME_STEADY_EVENT_DUAL) {
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

public void primeWavefrontSteadyConsume(
        inout PrimeIntegratorContext context,
        uint eventType,
        uint sourceQueue,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    primeShadeSteadyEvent(
            context,
            eventType,
            recordIndex,
            record,
            surface);
    bool transparent = primeWavefrontTransparentBranch(control);
    bool continuation = transparent
            ? primeAdvanceTransparentWavefrontRecord(
                    PrimeSteadyTransportPhase(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control,
                    false)
            : primeAdvanceOrdinaryWavefrontRecord(
                    PrimeSteadyTransportPhase(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control,
                    false);
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0,
            recordIndex,
            continuation);
}
