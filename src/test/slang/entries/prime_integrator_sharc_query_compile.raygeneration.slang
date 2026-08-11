#define SHARC_UPDATE 0
#define SHARC_QUERY 1
#define PRIME_SHARC_QUERY 1
#define PRIME_ENABLE_SER 1

#include "core/common.slang"
#include "material/material.slang"
#include "lighting/sharc.slang"
#include "integrator/integrator.slang"

[shader("raygeneration")]
void main()
{
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    SurfaceInteraction surface = primeTraceSurface(
            context, float3(0.0), float3(0.0, 0.0, 1.0));
    context.diagnostics.flags ^= surface.hitKind;
}
