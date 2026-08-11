#include "prime_abi.slang"
#include "core/light_tree_math.slang"

[[vk::binding(0, 3)]] RWStructuredBuffer<float4> primeLightTreeProbe;

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    primeLightTreeProbe[dispatchThreadId.x] = float4(
            primeLightBranchProbability(float2(1.0, 2.0), float2(3.0, 4.0)),
            primeLightEmitterCosineBound(
                    float3(0.0), float3(1.0), float3(2.0), 1.0, 0),
            0.0,
            1.0);
}
