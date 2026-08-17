#ifndef PRIME_SHARC_TRAINING_POLICY_SLANG
#define PRIME_SHARC_TRAINING_POLICY_SLANG

#include "lighting/sharc_training_records.slang"

struct PrimeIntegratedSharcTraining : IPrimeWavefrontSharcTraining {
    void recordHit(
            inout PrimeIntegratorContext context,
            PathState path,
            SurfaceInteraction surface,
            float3 direct) {
        if (!primeSharcTrainingPixel(path.pixel)) {
            return;
        }
        float3 emissive = primeEvaluateLocalHitEmission(
                context, path, surface, true);
        uint control = primeSharcTrainingControl(path.pixel);
        if (!primeSharcTrainingActive(control)) {
            if (primeSharcCacheEligible(path, surface, false)) {
                primeBeginSharcTraining(path, surface, direct);
            }
            return;
        }
        primeAccumulateSharcTrainingTarget(path, direct + emissive);
    }

    void recordMiss(
            inout PrimeIntegratorContext context,
            PathState path,
            IntegratorRecord integrator) {
        if (!primeSharcTrainingPixel(path.pixel)) {
            return;
        }
        PathState localPath = path;
        localPath.throughput = float3(1.0);
        primeCompleteSharcTraining(
                path,
                primeEvaluateEnvironmentContribution(
                        context, localPath, integrator));
    }

    void finish(PathState path) {
        primeCompleteSharcTraining(path, float3(0.0));
    }
};

#endif
