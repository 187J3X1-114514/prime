import "service/light/sample.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";

[[vk::binding(0, 3)]] RWStructuredBuffer<float4> primeLightingProbe;

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    IntegratorRecord integrator = ((IntegratorRecord*)primePush.sectionTableAddress)[0];
    primeLightingProbe[dispatchThreadId.x] = float4(
            primeEnvironmentRadiance(integrator, float3(0.0, 1.0, 0.0)),
            primeSunPdf());
}
