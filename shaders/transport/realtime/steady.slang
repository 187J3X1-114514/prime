#language slang 2026
module "transport/realtime/steady.slang";

import "policy/realtime/steady.slang";
import "policy/realtime/queue.slang";
import "policy/realtime/sharc.slang";
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
public static const uint PRIME_STEADY_EVENT_SUN = 1u;
public static const uint PRIME_STEADY_EVENT_AREA = 2u;

public uint primeSteadyEventQueue(uint eventType) {
    if (eventType == PRIME_STEADY_EVENT_SUN) {
        return PRIME_WAVEFRONT_AREA_QUEUE;
    }
    if (eventType == PRIME_STEADY_EVENT_AREA) {
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
    // NONE, SUN and AREA are a per-lane partition, so the queue is not subgroup-uniform.
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

public bool primeTryReuseSteadyIndirect<IndirectReuse, SharcTraining>(
        IndirectReuse indirectReuse,
        SharcTraining sharcTraining,
        inout PrimeIntegratorContext context,
        uint recordIndex,
        inout PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface)
    where IndirectReuse : IPrimeIndirectReuse
    where SharcTraining : IPrimeWavefrontSharcTraining
{
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    if (surface.hitKind != PRIME_HIT_SURFACE
            || primeWavefrontTransparentBranch(control)) {
        return false;
    }
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return false;
    }
    PrimeIndirectReuseResult reuse = indirectReuse.query(
            context, path, surface, true);
    if (!reuse.hit) {
        return false;
    }
    if (any(reuse.contribution > float3(0.0))) {
        primeAccumulateDeferredDirectLight(
                context, pixel, control, reuse.contribution);
    }
    sharcTraining.finish(path);
    record = primeWavefrontWithActive(record, false);
    primeStoreWavefrontTransportRecord(recordIndex, record);
    primeStoreWavefrontSurface(recordIndex, surface);
    primeStoreWavefrontSunFamilyProbability(recordIndex, 0u);
    return true;
}

public void primeWavefrontSharcSteadyClassify<IndirectReuse, SharcTraining>(
        IndirectReuse indirectReuse,
        SharcTraining sharcTraining,
        inout PrimeIntegratorContext context,
        uint launchIndex)
    where IndirectReuse : IPrimeIndirectReuse
    where SharcTraining : IPrimeWavefrontSharcTraining
{
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    SurfaceInteraction surface = primeFusedWavefrontTrace(
            context,
            recordIndex,
            primeWavefrontTransparentBranch(control));
    if (primeTryReuseSteadyIndirect(
            indirectReuse,
            sharcTraining,
            context,
            recordIndex,
            record,
            surface)) {
        return;
    }
    primeQueueSteadyEvent(context, recordIndex, record, surface);
}

public uint primeShadeSteadyEvent<SharcTraining>(
        SharcTraining sharcTraining,
        inout PrimeIntegratorContext context,
        uint eventType,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint storedSunProbability)
    where SharcTraining : IPrimeWavefrontSharcTraining
{
    PrimeTransparentLightEvent lightEvent;
    lightEvent.type = eventType == PRIME_STEADY_EVENT_SUN
            ? PRIME_TRANSPARENT_LIGHT_EVENT_SUN
            : eventType == PRIME_STEADY_EVENT_AREA
                    ? PRIME_TRANSPARENT_LIGHT_EVENT_AREA
                    : PRIME_TRANSPARENT_LIGHT_EVENT_NONE;
    lightEvent.storedSunProbability = storedSunProbability;
    if (eventType == PRIME_STEADY_EVENT_SUN) {
        PrimeFusedWavefrontLightResult light =
                primeShadeTransparentSunLightEvent(
                        sharcTraining,
                        context, recordIndex, record, surface, lightEvent);
        return light.storedSunProbability;
    }
    if (eventType != PRIME_STEADY_EVENT_AREA) {
        uint control = asuint(record.rayDirectionAndDenoiserControl.w);
        if (surface.hitKind == PRIME_HIT_SURFACE
                && !primeWavefrontTransparentBranch(control)) {
            PathState path = primeWavefrontPath(
                    primeWavefrontPixel(recordIndex), record);
            PrimeOpenPbrVolumeStack volumeStack =
                    primeWavefrontVolumeStack(record);
            if (primeApplySegmentMedium(context, path, surface, volumeStack)) {
                sharcTraining.recordHit(
                        context, path, surface, float3(0.0));
            }
        }
        return 0u;
    }

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
                    sharcTraining,
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

public void primeWavefrontSteadyConsume<SharcTraining>(
        SharcTraining sharcTraining,
        inout PrimeIntegratorContext context,
        uint eventType,
        uint sourceQueue,
        uint launchIndex)
    where SharcTraining : IPrimeWavefrontSharcTraining
{
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    uint storedSunProbability = primeShadeSteadyEvent(
            sharcTraining,
            context,
            eventType,
            recordIndex,
            record,
            surface,
            primeLoadWavefrontSunFamilyProbability(recordIndex));
    bool transparent = primeWavefrontTransparentBranch(control);
    if (!transparent && surface.hitKind == PRIME_HIT_NONE) {
        PathState path = primeWavefrontPath(
                primeWavefrontPixel(recordIndex), record);
        IntegratorRecord integrator;
        integrator.sunDirectionIntensity = float4(
                primePush.sunDirection,
                ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
        integrator.environmentRadiance = float4(0.0);
        sharcTraining.recordMiss(context, path, integrator);
    }
        bool continuation = transparent
            ? primeAdvanceTransparentWavefrontRecord(
                    PrimeSteadyTransportPhase(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control,
                    storedSunProbability,
                    false)
            : primeAdvanceOrdinaryWavefrontRecord(
                    PrimeSteadyTransportPhase(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control,
                    storedSunProbability,
                    false);
    if (!transparent && !continuation) {
        PrimeWavefrontTransportRecord finished =
                primeLoadWavefrontTransportRecord(recordIndex);
        sharcTraining.finish(primeWavefrontPath(
                primeWavefrontPixel(recordIndex), finished));
    }
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0,
            recordIndex,
            continuation);
}
