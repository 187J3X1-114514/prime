#include "core/numerical.slang"

struct PrimeNativeDebugPresentPushConstants
{
    uint2 sourceExtent;
    uint2 outputExtent;
    uint presentation;
};

[[vk::binding(0, 0)]] Texture2D<float4> primeNativeDebugInput;
[[vk::binding(1, 0)]]
[[vk::image_format("rgba8")]]
WTexture2D<unorm float4> primeNativeDebugOutput;
[[vk::push_constant]]
ConstantBuffer<PrimeNativeDebugPresentPushConstants> primeNativeDebugPush;

float3 primeNumericalCategoryColor(uint flags)
{
    if (flags == 0) return float3(0.0);
    uint nonFinite = flags & (PRIME_NUMERICAL_NAN
            | PRIME_NUMERICAL_POSITIVE_INFINITY
            | PRIME_NUMERICAL_NEGATIVE_INFINITY);
    if (nonFinite == PRIME_NUMERICAL_NAN) return float3(1.0, 0.0, 1.0);
    if (nonFinite == PRIME_NUMERICAL_POSITIVE_INFINITY) return float3(1.0, 0.0, 0.0);
    if (nonFinite == PRIME_NUMERICAL_NEGATIVE_INFINITY) return float3(0.0, 1.0, 1.0);
    if (nonFinite != 0) return float3(1.0);
    if ((flags & PRIME_NUMERICAL_FINITE_NEGATIVE) != 0) return float3(0.0, 0.25, 1.0);
    if ((flags & PRIME_NUMERICAL_INVALID_DIRECTION) != 0) return float3(1.0, 0.0, 0.5);
    if ((flags & PRIME_NUMERICAL_ABOVE_UNIT) != 0) return float3(0.5, 0.0, 1.0);
    return float3(1.0, 1.0, 0.0);
}

float3 primeNumericalStageColor(uint stage)
{
    switch (stage) {
        case PRIME_NUMERICAL_STAGE_CAMERA: return float3(0.75, 0.75, 0.75);
        case PRIME_NUMERICAL_STAGE_TRACE: return float3(0.35, 0.35, 0.35);
        case PRIME_NUMERICAL_STAGE_SURFACE: return float3(0.1, 0.9, 0.25);
        case PRIME_NUMERICAL_STAGE_MEDIUM: return float3(0.0, 0.8, 0.8);
        case PRIME_NUMERICAL_STAGE_EMISSION: return float3(1.0, 0.9, 0.1);
        case PRIME_NUMERICAL_STAGE_DIRECT_LIGHT: return float3(1.0, 0.45, 0.05);
        case PRIME_NUMERICAL_STAGE_BSDF_EVALUATE: return float3(0.15, 0.45, 1.0);
        case PRIME_NUMERICAL_STAGE_BSDF_SAMPLE: return float3(0.75, 0.1, 1.0);
        case PRIME_NUMERICAL_STAGE_PATH_ADVANCE: return float3(1.0, 0.1, 0.1);
        case PRIME_NUMERICAL_STAGE_TRANSPARENT_BRANCH: return float3(0.1, 1.0, 0.65);
        case PRIME_NUMERICAL_STAGE_GUIDE: return float3(0.45, 0.2, 1.0);
        case PRIME_NUMERICAL_STAGE_ACCUMULATION: return float3(1.0, 0.2, 0.55);
        case PRIME_NUMERICAL_STAGE_FINAL_OUTPUT: return float3(1.0);
        default: return float3(0.5);
    }
}

float3 primeNumericalFieldColor(uint field)
{
    switch (field) {
        case PRIME_NUMERICAL_FIELD_NON_FINITE: return float3(0.85, 0.85, 0.85);
        case PRIME_NUMERICAL_FIELD_NONNEGATIVE: return float3(0.1, 0.45, 1.0);
        case PRIME_NUMERICAL_FIELD_RADIANCE: return float3(1.0, 0.35, 0.05);
        case PRIME_NUMERICAL_FIELD_UNIT: return float3(0.65, 0.15, 1.0);
        case PRIME_NUMERICAL_FIELD_DIRECTION: return float3(1.0, 0.0, 0.5);
        case PRIME_NUMERICAL_FIELD_OUTPUT: return float3(0.15, 1.0, 0.35);
        default: return float3(0.5);
    }
}

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint2 pixel = dispatchThreadId.xy;
    if (any(pixel >= primeNativeDebugPush.outputExtent)) return;
    uint2 sourcePixel = min(
            (pixel * primeNativeDebugPush.sourceExtent)
                    / primeNativeDebugPush.outputExtent,
            primeNativeDebugPush.sourceExtent - uint2(1));
    float4 source = primeNativeDebugInput.Load(int3(sourcePixel, 0));
    float3 color = source.rgb;
    if (primeNativeDebugPush.presentation == 1) {
        color = primeNumericalCategoryColor(uint(round(source.r)));
    } else if (primeNativeDebugPush.presentation == 2) {
        uint flags = uint(round(source.r));
        uint stage = uint(round(source.g));
        bool nonFinite = (flags & (PRIME_NUMERICAL_NAN
                | PRIME_NUMERICAL_POSITIVE_INFINITY
                | PRIME_NUMERICAL_NEGATIVE_INFINITY)) != 0;
        color = flags == 0
                ? float3(0.0)
                : primeNumericalStageColor(stage) * (nonFinite ? 1.0 : 0.65);
    } else if (primeNativeDebugPush.presentation == 3) {
        uint flags = uint(round(source.r));
        uint field = uint(round(source.a));
        color = flags == 0 ? float3(0.0) : primeNumericalFieldColor(field);
    }
    primeNativeDebugOutput.Store(pixel, float4(color, 1.0));
}
