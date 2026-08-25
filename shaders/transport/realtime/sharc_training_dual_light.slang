#language slang 2026
module "transport/realtime/sharc_training_dual_light.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/realtime/light_event.slang";
import "math/sampling.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
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
import "bsdf/compact/contract/volume.slang";

public void primeShadeSharcTrainingDualLight(
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
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    float3 direct = primeEstimateDirectSun(
            context,
            integrator,
            surface,
            viewDirection,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            volumeStack);
    direct += primeEstimateDirectPreparedAreaLight(
            context,
            surface,
            viewDirection,
            area,
            volumeStack);
    primeStoreSharcTrainingJobDirect(recordIndex, direct);
}
