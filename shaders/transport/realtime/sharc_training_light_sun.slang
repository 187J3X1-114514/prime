#language slang 2026
module "transport/realtime/sharc_training_light_sun.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "math/sampling.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
import "service/trace/context.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/queue.slang";
import "state/realtime/sharc_training.slang";
import "state/realtime/surface.slang";
import "transport/direct/primary.slang";
import "transport/realtime/sharc_training_light_path.slang";
import "policy/transport/light_family.slang";
import "bsdf/compact/contract/volume.slang";

public void primeShadeSharcTrainingSun(
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
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    uint storedSunProbability =
            primeLoadWavefrontSunFamilyProbability(recordIndex);
    float3 direct = primeEstimateDirectSun(
            context,
            integrator,
            surface,
            -path.rayDirection,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            volumeStack,
            primeSunFamilyProbabilityFromStored(
                    storedSunProbability));
    primeStoreSharcTrainingJobDirect(recordIndex, direct);
}
