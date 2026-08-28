import "prime_abi.slang";
import "math/light_tree.slang";

[[vk::binding(0, 3)]] RWStructuredBuffer<float4> primeLightTreeProbe;

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    float probability = primeLightBranchProbability(
            float2(1.0, 2.0), float2(3.0, 4.0));
    primeLightTreeProbe[dispatchThreadId.x] = float4(
            probability,
            primeLightEmitterCosineBound(
                    float3(0.0), float3(1.0), float3(2.0), 1.0, 0),
            1.0 - probability,
            0.0);
}
