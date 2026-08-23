#language slang 2026
module "transport/realtime/landing.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "policy/realtime/landing.slang";
import "state/realtime/surface.slang";
import "state/realtime/queue.slang";
import "policy/realtime/sharc.slang";
import "math/sampling.slang";
import "service/light/sample.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";

import "transport/realtime/light_event_shade.slang";
import "transport/realtime/light_event_classify.slang";
import "transport/realtime/light.slang";
import "contract/realtime/light_event.slang";
import "transport/realtime/advance.slang";
import "transport/realtime/landing_publish.slang";
import "policy/transport/light_family.slang";

public uint primeShadePrimaryLandingSecondary(
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
        PrimeFusedWavefrontLightResult light =
                primeShadeTransparentSunLightEvent(
                        PrimeNoWavefrontSharcTraining(),
                        context, recordIndex, record, surface, lightEvent);
        return light.storedSunProbability;
    }

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

public void primeWavefrontPrimaryLandingSecondary(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    uint storedSunProbability = primeShadePrimaryLandingSecondary(
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
                    storedSunProbability,
                    true)
            : primeAdvanceOrdinaryWavefrontRecord(
                    PrimeLandingTransportPhase(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control,
                    storedSunProbability,
                    true);
    primePublishPrimaryLanding(recordIndex, transparent, continuation);
}
