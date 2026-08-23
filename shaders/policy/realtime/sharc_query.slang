#language slang 2026
module "policy/realtime/sharc_query.slang";

import "prime_abi_types.slang";
import "policy/realtime/sharc.slang";
import "service/trace/context.slang";
import "service/light/emission.slang";
import "transport/scatter/path_update.slang";
import "transport/surface/emission.slang";

// Keep third-party configuration local to the SHARC execution-mode adapter. Ordinary realtime
// entries never parse this implementation or acquire its descriptor-backed resources.
#define SHARC_UPDATE 0
#define SHARC_QUERY 1
#include "service/sharc/vendor_bridge.h"
#include "service/sharc/training_records.h"

public bool primeSharcCacheEligible(
        PathState path,
        SurfaceInteraction surface) {
    if (path.previousSharcEvent == PRIME_SHARC_EVENT_DISCRETE) {
        return false;
    }

    float voxelSize = primeSharcVoxelSize(
            surface.position, surface.geometricNormal);
    const float voxelDiagonalScale = 1.7320508075688772;
    if (surface.t <= voxelSize * voxelDiagonalScale) {
        return false;
    }
    if (path.previousSharcEvent == PRIME_SHARC_EVENT_GLOSSY) {
        float roughness = min(path.previousSharcRoughness, 0.99);
        float alpha = roughness * roughness;
        float alphaSquared = alpha * alpha;
        float footprintRadius = surface.t * sqrt(
                0.5 * alphaSquared / max(1.0 - alphaSquared, 1.0e-6));
        if (footprintRadius <= voxelSize) {
            return false;
        }
    }
    return true;
}

public struct PrimeSharcIndirectReuse : IPrimeIndirectReuse {
    public PrimeIndirectReuseResult query(
            inout PrimeIntegratorContext context,
            PathState path,
            SurfaceInteraction surface,
            bool previousUsedAreaNee) {
        PrimeIndirectReuseResult result;
        result.contribution = float3(0.0);
        result.hit = false;
        if (!primeSharcCacheEligible(path, surface)) {
            return result;
        }
        // Training paths must trace their complete transport suffix without cached feedback.
        if (primeSharcTrainingBypassesCache(path)) {
            return result;
        }

        float directionWeight = path.previousSharcEvent == PRIME_SHARC_EVENT_GLOSSY
                ? 1.0 - path.previousSharcRoughness
                : 0.0;
        SharcHitData hit = primeSharcHitData(
                surface.position,
                surface.geometricNormal,
                -path.rayDirection,
                directionWeight,
                primeSharcMaterialDemodulation(surface),
                primeEvaluateLocalHitEmission(
                        context, path, surface, previousUsedAreaNee));
        float3 radiance;
        if (!SharcGetCachedRadiance(
                primeSharcParameters(), hit, radiance, false)) {
            return result;
        }
        primeCompleteSharcTraining(path, radiance);
        result.contribution = path.throughput * radiance;
        result.hit = true;
        return result;
    }
}

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

