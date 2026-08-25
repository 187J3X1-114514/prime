#language slang 2026
module "transport/direct/policy.slang";

import "prime_abi_types.slang";
import "math/sampling.slang";
import "service/trace/context.slang";
import "contract/light.slang";
import "transport/direct/primary.slang";
import "transport/direct/selection.slang";
import "bsdf/compact/contract/volume.slang";

public interface IPrimeSecondaryAreaNee {
    bool enabled();
    PrimeDirectLightingSplit estimatePrimary(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            bool conditionalTransparentBranch);

    float3 estimate(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack);
}

public interface IPrimeSecondarySunNee {
    float3 estimate(
            inout PrimeIntegratorContext context,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            float3 pathThroughput,
            uint pathIndex);
}

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
        // The merged Light stage consumes the persisted hit with this exact pre-scatter path.
        return float3(0.0);
    }
}

public struct PrimeNoSecondaryAreaNee : IPrimeSecondaryAreaNee {
    bool enabled() { return false; }
    PrimeDirectLightingSplit estimatePrimary(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            bool conditionalTransparentBranch) {
        PrimeDirectLightingSplit result;
        result.diffuse = float3(0.0);
        result.specular = float3(0.0);
        result.direction = float3(0.0);
        return result;
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

public float3 primeEstimateDirectLighting<SunNee, AreaNee>(
        SunNee sunNee,
        AreaNee areaNee,
        inout PrimeIntegratorContext context,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeOpenPbrVolumeStack volumeStack,
        float3 pathThroughput,
        uint pathIndex)
    where SunNee : IPrimeSecondarySunNee
    where AreaNee : IPrimeSecondaryAreaNee
{
    float3 result = sunNee.estimate(
            context,
            integrator,
            surface,
            viewDirection,
            preparedSample,
            volumeStack,
            pathThroughput,
            pathIndex);
    result += areaNee.estimate(
            context,
            surface,
            viewDirection,
            preparedSample,
            volumeStack);
    return result;
}
