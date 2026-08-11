#define PRIME_ENABLE_SER 1
#include "integrator/integrator.slang"

[shader("raygeneration")]
void main()
{
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    SurfaceInteraction surface = primeTraceSurface(
            context, float3(0.0), float3(0.0, 0.0, 1.0));
    LightSample shadowLight;
    shadowLight.direction = float3(0.0, 0.0, 1.0);
    shadowLight.distance = 1.0;
    shadowLight.radiance = float3(1.0);
    shadowLight.pdf = 1.0;
    shadowLight.isDelta = 0;
    PrimeShadowTrace shadow = primeTraceShadow(
            context,
            float3(0.0),
            float3(0.0, 1.0, 0.0),
            shadowLight,
            primeEmptyVolumeStack());
    if (surface.hitKind == PRIME_HIT_SURFACE)
    {
        context.diagnostics.flags ^= surface.materialControl | asuint(shadow.hitDistance);
    }
}
