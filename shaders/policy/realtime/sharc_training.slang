#language slang 2026
module "policy/realtime/sharc_training.slang";

import "prime_abi_types.slang";
import "policy/realtime/sharc.slang";
import "service/trace/context.slang";
import "transport/scatter/path_update.slang";
import "transport/surface/emission.slang";

// Training sees hash-grid addressing and material demodulation, but no cache query/update API.
#define SHARC_UPDATE 0
#define SHARC_QUERY 0
#include "service/sharc/vendor_bridge.h"
#include "service/sharc/eligibility.h"
#include "service/sharc/training_records.h"

public struct PrimeIntegratedSharcTraining : IPrimeWavefrontSharcTraining {
    public void recordHit(
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
        bool anchorEligible = primeSharcCacheEligible(path, surface);
        if (!primeSharcTrainingActive(control)) {
            if (anchorEligible) {
                primeBeginSharcTraining(path, surface, direct);
            }
            return;
        }
        primeRecordSharcTrainingHit(
                path,
                surface,
                direct,
                direct + emissive,
                anchorEligible);
    }

    public void recordMiss(
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

    public void finish(PathState path) {
        primeCompleteSharcTraining(path, float3(0.0));
    }
};
