#language slang 2026
module "transport/direct/deferred.slang";

import "prime_abi_types.slang";
import "contract/light.slang";
import "math/sampling.slang";
import "service/trace/context.slang";
import "transport/direct/contract.slang";
import "transport/direct/area.slang";
import "bsdf/compact/contract/volume.slang";

// Primary construction evaluates its guide split immediately; only secondary vertices defer the
// light work into a later wavefront stage.
public struct PrimeStagedSecondaryAreaNee : IPrimeSecondaryAreaNee {
    bool enabled() { return true; }
    PrimeDirectLightingSplit estimatePrimary(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            bool conditionalTransparentBranch) {
        return primeEstimatePrimaryNonsunDirect(
                context,
                surface,
                viewDirection,
                preparedSample,
                volumeStack,
                conditionalTransparentBranch);
    }
    float3 estimate(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack) {
        return float3(0.0);
    }
}
