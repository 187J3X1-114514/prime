#ifndef PRIME_SHARC_TRAINING_POLICY_SLANG
#define PRIME_SHARC_TRAINING_POLICY_SLANG

#include "lighting/sharc_training_events.slang"

struct PrimeIntegratedSharcTraining : IPrimeWavefrontSharcTraining {
    void recordHit(
            inout PrimeIntegratorContext context,
            PathState path,
            SurfaceInteraction surface,
            float3 direct) {
        if (!primeSharcTrainingCarrier(path)) {
            return;
        }
        float3 emissive = primeEvaluateLocalHitEmission(
                context, path, surface, true);
        primeStoreSharcTrainingHit(path, surface, direct, emissive);
    }

    void recordMiss(
            inout PrimeIntegratorContext context,
            PathState path,
            IntegratorRecord integrator) {
        if (!primeSharcTrainingCarrier(path)) {
            return;
        }
        PathState localPath = path;
        localPath.throughput = float3(1.0);
        primeStoreSharcTrainingMiss(
                path,
                primeEvaluateEnvironmentContribution(
                        context, localPath, integrator));
    }
};

#endif
