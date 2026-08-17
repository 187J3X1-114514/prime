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
        SurfaceInteraction surface,
        bool diagnosticSample) {
    if (path.previousSharcEvent == PRIME_SHARC_EVENT_DISCRETE) {
        primeRecordSharcDiagnostic(
                diagnosticSample, PRIME_SHARC_DIAGNOSTIC_DISCRETE_SKIP);
        return false;
    }

    float voxelSize = primeSharcVoxelSize(
            surface.position, surface.geometricNormal);
    const float voxelDiagonalScale = 1.7320508075688772;
    if (surface.t <= voxelSize * voxelDiagonalScale) {
        primeRecordSharcDiagnostic(
                diagnosticSample, PRIME_SHARC_DIAGNOSTIC_SHORT_SKIP);
        return false;
    }
    if (path.previousSharcEvent == PRIME_SHARC_EVENT_GLOSSY) {
        float roughness = min(path.previousSharcRoughness, 0.99);
        float alpha = roughness * roughness;
        float alphaSquared = alpha * alpha;
        float footprintRadius = surface.t * sqrt(
                0.5 * alphaSquared / max(1.0 - alphaSquared, 1.0e-6));
        if (footprintRadius <= voxelSize) {
            primeRecordSharcDiagnostic(
                    diagnosticSample, PRIME_SHARC_DIAGNOSTIC_GLOSSY_SKIP);
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
        bool diagnosticSample = primeSharcDiagnosticSample(path);
        primeRecordSharcDiagnostic(
                diagnosticSample, PRIME_SHARC_DIAGNOSTIC_QUERY);
        if (!primeSharcCacheEligible(path, surface, diagnosticSample)) {
            return result;
        }
        // Anchors never query after opening their root. Bootstrap paths bypass the root itself,
        // then terminate at the first later cache hit; cache misses continue in the main queue.
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
        HashGridKey hashKey = HashGridComputeSpatialHash(
                hit.positionWorld,
                hit.normalWorld,
                primeSharcParameters().hashGridParameters);
        if (primeSharcTrainingRejectsSelfFeedback(path, hashKey)) {
            return result;
        }
        float3 radiance;
        primeRecordSharcDiagnostic(
                diagnosticSample, PRIME_SHARC_DIAGNOSTIC_LOOKUP);
        if (!SharcGetCachedRadiance(
                primeSharcParameters(), hit, radiance, false)) {
            return result;
        }
        primeRecordSharcDiagnostic(
                diagnosticSample, PRIME_SHARC_DIAGNOSTIC_HIT);
        primeCompleteSharcTraining(path, radiance);
        result.contribution = path.throughput * radiance;
        result.hit = true;
        return result;
    }
}

#endif
