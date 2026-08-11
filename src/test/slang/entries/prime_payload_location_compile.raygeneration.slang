#include "prime_abi_types.slang"
#include "rt/shadow_payload.slang"

[[vk::binding(0, 0)]]
RaytracingAccelerationStructure primeTestScene;

[shader("raygeneration")]
void main()
{
    RayDesc ray;
    ray.Origin = float3(0.0);
    ray.Direction = float3(0.0, 0.0, 1.0);
    ray.TMin = 0.0;
    ray.TMax = 1.0;

    [[vk::location(0)]] PrimeShadowPayload shadowPayload = {};
    TraceRay(primeTestScene, RAY_FLAG_NONE, 0xff, 3, 1, 1, ray, shadowPayload);

    [[vk::location(1)]] TracePayload surfacePayload = {};
    TraceRay(primeTestScene, RAY_FLAG_NONE, 0xff, 0, 1, 0, ray, surfacePayload);
}
