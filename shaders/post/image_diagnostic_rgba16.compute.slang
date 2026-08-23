import "post/image_diagnostic_common.slang";

[[vk::binding(1, 0)]]
[[vk::image_format("rgba16f")]]
WTexture2D<float4> primeImageDiagnosticOutput;

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint2 local = dispatchThreadId.xy;
    if (any(local >= primeImageDiagnosticPush.regionExtent)) return;
    primeImageDiagnosticOutput.Store(
            primeImageDiagnosticPush.regionOrigin + local,
            primeImageDiagnosticPixel(local));
}
