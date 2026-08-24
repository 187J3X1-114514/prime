#language slang 2026
module "transport/realtime/sharc_training_light_classify.slang";

import "prime_abi.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "contract/path.slang";
import "contract/realtime/light_event.slang";
import "math/sampling.slang";
import "model/atmosphere/sky.slang";
import "policy/realtime/queue.slang";
import "policy/runtime/config.slang";
import "policy/transport/light_family.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dispatch/traits.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/sharc_training.slang";
import "state/realtime/surface.slang";
import "transport/direct/selection.slang";
import "transport/realtime/light.slang";
import "bsdf/compact/contract/volume.slang";

public uint primeSharcTrainingEventQueue(uint eventType) {
    if (eventType == PRIME_REALTIME_LIGHT_EVENT_SUN) {
        return PRIME_WAVEFRONT_AREA_QUEUE;
    }
    if (eventType == PRIME_REALTIME_LIGHT_EVENT_AREA) {
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
    uint storedSunProbability = 0u;
    if (surface.hitKind == PRIME_HIT_SURFACE) {
        uint2 pixel = primeSharcTrainingJobPixel(recordIndex);
        PathState path = primeWavefrontPath(pixel, record);
        PrimeOpenPbrVolumeStack volumeStack =
                primeWavefrontVolumeStack(record);
        float3 viewDirection = -path.rayDirection;
        PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
                surface, viewDirection, volumeStack, primeUsesAirGap());
        if (primeClosureSupports(
                closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
            IntegratorRecord integrator;
            integrator.sunDirectionIntensity = float4(
                    primePush.sunDirection,
                    ATM_SPACE_SUN_INTENSITY
                            * primeSunRadianceMultiplier());
            integrator.environmentRadiance = float4(0.0);
            float sunProbability = primeMixedWavefrontSunProbability(
                    integrator, surface, viewDirection);
            storedSunProbability =
                    primeStoredSunFamilyProbability(sunProbability);
            PrimePreparedSampleBase preparedSample =
                    primePrepareSampleBase(
                            primeMakeSampleBase(path, path.bounce + 1u));
            eventType = primeSecondarySamplesSunLight(
                    preparedSample, sunProbability)
                    ? PRIME_REALTIME_LIGHT_EVENT_SUN
                    : PRIME_REALTIME_LIGHT_EVENT_AREA;
        }
    }
    primeStoreWavefrontSunFamilyProbability(
            recordIndex, storedSunProbability);
    primeAppendWavefrontContinuation(
            PrimeScalarWavefrontContinuationAppender(),
            primeSharcTrainingEventQueue(eventType),
            recordIndex,
            true);
}
