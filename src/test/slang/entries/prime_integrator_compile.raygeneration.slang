#define PRIME_ENABLE_SER 1
import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "service/light/sample.slang";
import "service/bsdf/support/volume_transition.slang";
import "service/trace/context.slang";
import "service/trace/surface.slang";
import "transport/direct/shadow.slang";

[shader("raygeneration")]
void main()
{
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    SurfaceInteraction surface = primeTraceSurface(
            float3(0.0), float3(0.0, 0.0, 1.0));
    LightSample shadowLight;
    shadowLight.direction = float3(0.0, 0.0, 1.0);
    shadowLight.distance = 1.0;
    shadowLight.radiance = float3(1.0);
    shadowLight.pdf = 1.0;
    shadowLight.isDelta = 0;
    PrimeShadowTrace shadow = primeTraceShadow(
            context,
            surface,
            shadowLight,
            primeEmptyVolumeStack());
}
