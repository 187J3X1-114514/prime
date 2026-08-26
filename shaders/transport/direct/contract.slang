#language slang 2026
module "transport/direct/contract.slang";

import "prime_abi_types.slang";
import "math/sampling.slang";
import "service/trace/context.slang";
import "contract/light.slang";
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
