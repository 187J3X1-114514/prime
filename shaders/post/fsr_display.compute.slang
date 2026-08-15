import "core/auto_exposure.slang";
import "post/display_transform.slang";

struct PrimeFsrDisplayPushConstants
{
    uint2 outputExtent;
    uint debugView;
    float finalExposureMultiplier;
};

[[vk::binding(0, 0)]] Texture2D<float4> primeFsrLinearOutput;
[[vk::binding(1, 0)]]
[[vk::image_format("rgba8")]]
WTexture2D<unorm float4> primeDisplayOutput;
[[vk::binding(2, 0)]] StructuredBuffer<PrimeAutoExposureState> primeAutoExposureState;
[[vk::push_constant]] ConstantBuffer<PrimeFsrDisplayPushConstants> primeFsrDisplayPush;

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint2 pixel = dispatchThreadId.xy;
    if (any(pixel >= primeFsrDisplayPush.outputExtent)) return;

    float3 radiance = primeFsrLinearOutput.Load(int3(pixel, 0)).rgb;
    if (primeFsrDisplayPush.debugView != 0) {
        primeDisplayOutput.Store(pixel, float4(clamp(radiance, 0.0, 1.0), 1.0));
        return;
    }
    if (any(isnan(radiance)) || any(isinf(radiance))) radiance = float3(0.0);
    primeDisplayOutput.Store(
            pixel,
            float4(
                    primeDisplayTransformToSrgb(
                            max(radiance, float3(0.0)),
                            exp2(primeAutoExposureState[0].exposureEv)
                                    * primeFsrDisplayPush.finalExposureMultiplier),
                    1.0));
}
