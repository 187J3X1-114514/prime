#language slang 2026
module "transport/direct/immediate.slang";

import "prime_abi_types.slang";
import "contract/light.slang";
import "math/sampling.slang";
import "service/trace/context.slang";
import "transport/direct/contract.slang";
import "transport/direct/area.slang";
import "transport/direct/primary.slang";
import "bsdf/compact/contract/volume.slang";

public struct PrimeImmediateSecondarySunNee : IPrimeSecondarySunNee {
    float3 estimate(
            inout PrimeIntegratorContext context,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            float3 pathThroughput,
            uint pathIndex) {
        return primeEstimateDirectSun(
                context,
                integrator,
                surface,
                viewDirection,
                primeSobolSample2D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                        PRIME_SAMPLE_DIMENSION_PRIMARY),
                volumeStack);
    }
}

public struct PrimeImmediateSecondaryAreaNee : IPrimeSecondaryAreaNee {
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
        return primeEstimateDirectAreaLight(
                context,
                surface,
                viewDirection,
                primeSobolSample3D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                        PRIME_SAMPLE_DIMENSION_PRIMARY).xy,
                primeSobolSample2D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                        PRIME_SAMPLE_DIMENSION_SECONDARY),
                volumeStack);
    }
}
