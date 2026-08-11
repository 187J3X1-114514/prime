#include "core/starmap.slang"

[[vk::binding(0, 3)]] RWStructuredBuffer<float4> primeAtmosphereProbe;

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    IntegratorRecord integrator = ((IntegratorRecord*)primePush.sectionTableAddress)[0];
    float3 direction = normalize(float3(0.25, 0.5, 1.0));
    primeAtmosphereProbe[dispatchThreadId.x] = float4(
            primeAtmosphereSky(direction, primePush.sunDirection)
                    + primeStarmapRadiance(
                            integrator, primePush.cameraPosition, direction),
            1.0);
}
