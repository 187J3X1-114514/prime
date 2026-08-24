#language slang 2026
module "policy/realtime/sharc_query.slang";

import "prime_abi_types.slang";
import "service/trace/context.slang";
import "service/light/emission.slang";
import "transport/scatter/path_update.slang";
import "transport/surface/emission.slang";

// Keep third-party configuration local to the SHARC execution-mode adapter. Ordinary realtime
// entries never parse this implementation or acquire its descriptor-backed resources.
#define SHARC_UPDATE 0
#define SHARC_QUERY 1
#include "service/sharc/vendor_bridge.h"
#include "service/sharc/eligibility.h"

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
        result.contribution = path.throughput * radiance;
        result.hit = true;
        return result;
    }
};

