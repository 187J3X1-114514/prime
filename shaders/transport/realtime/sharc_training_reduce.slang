#language slang 2026
module "transport/realtime/sharc_training_reduce.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/path.slang";
import "model/atmosphere/sky.slang";
import "policy/realtime/sharc_training.slang";
import "policy/runtime/config.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/sharc_training.slang";
import "state/realtime/surface.slang";

public void primeReduceSharcTrainingChunk(
        inout PrimeIntegratorContext context,
        uint trainingIndex,
        uint2 pixel) {
    uint carrierIndex = primeWavefrontIndex(pixel, 0u);
    uint metadata = primeWavefrontPaths[carrierIndex].psrPacked.x;
    if (metadata == 0u) {
        return;
    }

    uint jobCount = metadata & PRIME_SHARC_TRAINING_JOB_COUNT_MASK;
    bool terminal = (metadata & PRIME_SHARC_TRAINING_TERMINAL) != 0u;
    uint previousStoredSunProbability = 0u;
    if (jobCount != 0u) {
        uint firstJobIndex = primeSharcTrainingJobIndex(trainingIndex, 0u);
        previousStoredSunProbability =
                primeWavefrontPaths[firstJobIndex]
                        .transport.sourceAndPathControl.z;
    }

    [loop]
    for (uint localBounce = 0u;
            localBounce < jobCount;
            localBounce++) {
        uint jobIndex = primeSharcTrainingJobIndex(
                trainingIndex, localBounce);
        PrimeWavefrontTransportRecord record =
                primeLoadWavefrontTransportRecord(jobIndex);
        PathState path = primeWavefrontPath(pixel, record);
        path.sourceReserved = previousStoredSunProbability;
        path.previousLightNormal =
                primeSharcTrainingJobPreviousLightNormal(jobIndex);
        SurfaceInteraction surface = primeLoadWavefrontSurface(jobIndex);
        if (surface.hitKind == PRIME_HIT_SURFACE) {
            PrimeIntegratedSharcTraining().recordHit(
                    context,
                    path,
                    surface,
                    primeLoadSharcTrainingJobDirect(jobIndex));
        } else {
            IntegratorRecord integrator;
            integrator.sunDirectionIntensity = float4(
                    primePush.sunDirection,
                    ATM_SPACE_SUN_INTENSITY
                            * primeSunRadianceMultiplier());
            integrator.environmentRadiance = float4(0.0);
            PrimeIntegratedSharcTraining().recordMiss(
                    context, path, integrator);
        }
        previousStoredSunProbability =
                primeLoadWavefrontSunFamilyProbability(jobIndex);
    }

    if (terminal) {
        PrimeWavefrontTransportRecord carrier =
                primeLoadWavefrontTransportRecord(carrierIndex);
        PathState terminalPath = primeWavefrontPath(pixel, carrier);
        terminalPath.previousLightNormal =
                primeSharcTrainingJobPreviousLightNormal(carrierIndex);
        PrimeIntegratedSharcTraining().finish(terminalPath);
    } else {
        primeWavefrontPaths[carrierIndex]
                .transport.sourceAndPathControl.z =
                        previousStoredSunProbability;
    }
    primeWavefrontPaths[carrierIndex].psrPacked.x = 0u;
}
