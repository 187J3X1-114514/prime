#language slang 2026
module "transport/realtime/sharc_training_light_area.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/realtime/light_event.slang";
import "math/sampling.slang";
import "service/light/sample.slang";
import "service/trace/context.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/queue.slang";
import "state/realtime/sharc_training.slang";
import "state/realtime/surface.slang";
import "transport/direct/primary.slang";
import "transport/direct/selection.slang";
import "transport/realtime/sharc_training_light_path.slang";
import "policy/transport/light_family.slang";
import "bsdf/compact/contract/volume.slang";

public void primeShadeSharcTrainingArea(
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    PathState path = primeSharcTrainingLightPath(
            recordIndex, record);
    PrimeOpenPbrVolumeStack volumeStack =
            primeWavefrontVolumeStack(record);
    PrimePreparedSampleBase preparedSample = primePrepareSampleBase(
            primeMakeSampleBase(path, path.bounce + 1u));
    float3 viewDirection = -path.rayDirection;
    PrimeRealtimeAreaSampleInput lightData;
    lightData.position = surface.position;
    lightData.receiverNormal =
            primeAreaLightReceiverNormal(surface, viewDirection);
    lightData.treeSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
            PRIME_SAMPLE_DIMENSION_PRIMARY).xy;
    lightData.positionSample = primeSobolSample2D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
            PRIME_SAMPLE_DIMENSION_SECONDARY);
    AreaLightSample area = primeSampleAreaLight(
            lightData.position,
            lightData.receiverNormal,
            lightData.treeSample,
            lightData.positionSample);
    uint storedSunProbability =
            primeLoadWavefrontSunFamilyProbability(recordIndex);
    float3 direct = primeEstimateDirectPreparedAreaLight(
            context,
            surface,
            viewDirection,
            area,
            volumeStack,
            1.0 - primeSunFamilyProbabilityFromStored(
                    storedSunProbability));
    primeStoreSharcTrainingJobDirect(recordIndex, direct);
}
