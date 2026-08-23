#define PRIME_ENABLE_SER 1

#include "integrator/integrator.slang"
#include "realtime/sharc_query_policy.slang"

[shader("raygeneration")]
void main()
{
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    SurfaceInteraction surface = primeTraceSurface(
            float3(0.0), float3(0.0, 0.0, 1.0));
    PathState path;
    path.previousSharcEvent = PRIME_SHARC_EVENT_DISCRETE;
    PrimeIndirectReuseResult reuse = PrimeSharcIndirectReuse().query(
            context, path, surface, false);
}
