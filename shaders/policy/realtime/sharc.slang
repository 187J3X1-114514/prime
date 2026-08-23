#language slang 2026
module "policy/realtime/sharc.slang";

import "prime_abi_types.slang";
import "service/trace/context.slang";

public interface IPrimeWavefrontSharcTraining {
    void recordHit(
            inout PrimeIntegratorContext context,
            PathState path,
            SurfaceInteraction surface,
            float3 direct);
    void recordMiss(
            inout PrimeIntegratorContext context,
            PathState path,
            IntegratorRecord integrator);
    void finish(PathState path);
}

public struct PrimeNoWavefrontSharcTraining : IPrimeWavefrontSharcTraining {
    void recordHit(
            inout PrimeIntegratorContext context,
            PathState path,
            SurfaceInteraction surface,
            float3 direct) {}
    void recordMiss(
            inout PrimeIntegratorContext context,
            PathState path,
            IntegratorRecord integrator) {}
    void finish(PathState path) {}
};
