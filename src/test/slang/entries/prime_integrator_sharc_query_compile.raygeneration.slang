#define PRIME_ENABLE_SER 1

import "prime_abi_types.slang";
import "service/trace/context.slang";
import "service/trace/surface.slang";
import "policy/realtime/sharc_query.slang";
import "transport/scatter/path_update.slang";
import "transport/surface/emission.slang";

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
