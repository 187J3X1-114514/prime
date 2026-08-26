#language slang 2026
module "policy/realtime/direct.slang";

import "prime_abi_types.slang";
import "math/sampling.slang";
import "service/trace/context.slang";
import "transport/direct/contract.slang";
import "bsdf/compact/contract/volume.slang";

public struct PrimeStagedSecondarySunNee : IPrimeSecondarySunNee {
    float3 estimate(
            inout PrimeIntegratorContext context,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            float3 pathThroughput,
            uint pathIndex) {
        return float3(0.0);
    }
};
