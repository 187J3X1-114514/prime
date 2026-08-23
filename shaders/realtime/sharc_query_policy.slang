#ifndef PRIME_SHARC_QUERY_POLICY_SLANG
#define PRIME_SHARC_QUERY_POLICY_SLANG

// Keep third-party configuration local to the SHARC execution-mode adapter. Ordinary realtime
// entries never parse this implementation or acquire its descriptor-backed resources.
#define SHARC_UPDATE 0
#define SHARC_QUERY 1
#include "lighting/sharc.slang"
#include "lighting/sharc_training_records.slang"

bool primeSharcCacheEligible(
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

struct PrimeSharcIndirectReuse : IPrimeIndirectReuse {
    PrimeIndirectReuseResult query(
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

#endif
