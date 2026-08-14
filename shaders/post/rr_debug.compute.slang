import "core/image_access.slang";
import "core/color_space.slang";

import "material/default_material.slang";
import "core/auto_exposure.slang";
import "post/display_transform.slang";

[[vk::binding(0, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> rrDebugInputColor;
[[vk::binding(1, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> rrDebugMotion;
[[vk::binding(2, 0)]] [[vk::image_format("rg16f")]] readonly RWTexture2D<float4> rrDebugSpecularMotion;
[[vk::binding(3, 0)]] [[vk::image_format("r32f")]] readonly RWTexture2D<float4> rrDebugDepth;
[[vk::binding(4, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> rrDebugNormalRoughness;
[[vk::binding(5, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> rrDebugDiffuseAlbedo;
[[vk::binding(6, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> rrDebugSpecularAlbedo;
[[vk::binding(7, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> rrDebugNoisySpecular;
[[vk::binding(8, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> rrDebugOutput;
[[vk::binding(9, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> rrDebugDisplay;
[[vk::binding(10, 0)]] StructuredBuffer<PrimeAutoExposureState> primeAutoExposureState;

struct PrimeRrDebugPushConstants {
    uint view;
    uint fullscreen;
    uint jitterPhase;
    uint jitterPhaseCount;
    float finalExposureMultiplier;
};
[[vk::push_constant]] ConstantBuffer<PrimeRrDebugPushConstants> rrDebugPush;

static const uint RR_OVERVIEW = 1u;
static const uint RR_INPUT_COLOR = 2u;
static const uint RR_MOTION = 3u;
static const uint RR_SPECULAR_MOTION = 4u;
static const uint RR_DEPTH = 5u;
static const uint RR_JITTER = 6u;
static const uint RR_NORMALS = 7u;
static const uint RR_ROUGHNESS = 8u;
static const uint RR_DIFFUSE_ALBEDO = 9u;
static const uint RR_SPECULAR_ALBEDO = 10u;
static const uint RR_SPECULAR_HIT_DISTANCE = 11u;
static const uint RR_OUTPUT = 12u;
static const float3 RR_INVALID = float3(1.0, 0.0, 1.0);

bool rrFinite(float value) {
    return !isnan(value) && !isinf(value);
}

bool rrFinite(float2 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

bool rrFinite(float3 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

float rrHalton(uint index, uint base) {
    float result = 0.0;
    float fraction = 1.0;
    uint value = index;
    while (value > 0u) {
        fraction /= float(base);
        result += fraction * float(value % base);
        value /= base;
    }
    return result;
}

int2 rrSourcePixel(float2 uv, int2 size) {
    return clamp(int2(uv * float2(size)), int2(0), size - int2(1));
}

float3 rrHdr(float3 radiance) {
    if (!rrFinite(radiance)) return RR_INVALID;
    return primeDisplayTransformToSrgb(
            max(radiance, float3(0.0)),
            exp2(primeAutoExposureState[0].exposureEv)
                    * rrDebugPush.finalExposureMultiplier);
}

float3 rrJitterPlot(float2 uv) {
    float3 color = float3(0.025);
    float axes = min(abs(uv.x - 0.5), abs(uv.y - 0.5));
    if (axes < 0.003) color = float3(0.16);
    uint count = max(rrDebugPush.jitterPhaseCount, 32u);
    for (uint index = 1u; index <= count; ++index) {
        float2 point = float2(rrHalton(index, 2u), rrHalton(index, 3u));
        float radius = length((uv - point) * float2(1.0));
        if (radius < 0.012) {
            color = index == rrDebugPush.jitterPhase
                    ? float3(1.0, 0.32, 0.05)
                    : float3(0.25, 0.72, 1.0);
        }
    }
    return color;
}

float3 rrMotionColor(float2 motion, int2 size) {
    float2 motionPixels = motion * float2(size);
    if (!rrFinite(motionPixels)) return RR_INVALID;
    float3 positive = max(motionPixels.x, 0.0) * float3(1.0, 0.0, 0.0)
            + max(motionPixels.y, 0.0) * float3(0.0, 1.0, 0.0);
    float3 negative = max(-motionPixels.x, 0.0) * float3(0.0, 1.0, 1.0)
            + max(-motionPixels.y, 0.0) * float3(1.0, 0.0, 1.0);
    return clamp((positive + negative) * 0.08, 0.0, 1.0);
}

float3 rrPanel(uint panel, float2 uv) {
    int2 lowSize = primeReadonlyImageSize(rrDebugInputColor);
    int2 fullSize = primeReadonlyImageSize(rrDebugOutput);
    int2 lowPixel = rrSourcePixel(uv, lowSize);
    int2 fullPixel = rrSourcePixel(uv, fullSize);
    float4 inputColor = primeReadonlyImageLoad(rrDebugInputColor, lowPixel);
    if (panel == RR_INPUT_COLOR) return rrHdr(inputColor.rgb);
    if (panel == RR_MOTION) {
        return rrMotionColor(primeReadonlyImageLoad(rrDebugMotion, lowPixel).xy, lowSize);
    }
    if (panel == RR_SPECULAR_MOTION) {
        return rrMotionColor(primeReadonlyImageLoad(rrDebugSpecularMotion, lowPixel).xy, lowSize);
    }
    if (panel == RR_DEPTH) {
        float depth = primeReadonlyImageLoad(rrDebugDepth, lowPixel).x;
        if (!rrFinite(depth)) return RR_INVALID;
        float mapped = clamp(log2(1.0 + max(depth, 0.0)) / 16.0, 0.0, 1.0);
        return float3(mapped);
    }
    if (panel == RR_JITTER) return rrJitterPlot(uv);
    float4 normalRoughness = primeReadonlyImageLoad(rrDebugNormalRoughness, lowPixel);
    if (!rrFinite(normalRoughness.xyz) || !rrFinite(normalRoughness.a)) return RR_INVALID;
    if (panel == RR_NORMALS) return clamp(normalRoughness.xyz * 0.5 + 0.5, 0.0, 1.0);
    if (panel == RR_ROUGHNESS) return float3(clamp(normalRoughness.a, 0.0, 1.0));
    if (panel == RR_DIFFUSE_ALBEDO) {
        float3 albedo = primeReadonlyImageLoad(rrDebugDiffuseAlbedo, lowPixel).rgb;
        if (!rrFinite(albedo)) return RR_INVALID;
        albedo = max(albedo, float3(0.0));
        return clamp(primeEncodeSrgb(primeLinearRec2020ToLinearBt709(albedo)), 0.0, 1.0);
    }
    if (panel == RR_SPECULAR_ALBEDO) {
        float3 albedo = primeReadonlyImageLoad(rrDebugSpecularAlbedo, lowPixel).rgb;
        if (!rrFinite(albedo)) return RR_INVALID;
        albedo = max(albedo, float3(0.0));
        return clamp(primeEncodeSrgb(primeLinearRec2020ToLinearBt709(albedo)), 0.0, 1.0);
    }
    if (panel == RR_SPECULAR_HIT_DISTANCE) {
        float hitDistance = primeReadonlyImageLoad(rrDebugNoisySpecular, lowPixel).a;
        if (!rrFinite(hitDistance)) return RR_INVALID;
        float mapped = clamp(log2(1.0 + max(hitDistance, 0.0)) / 16.0, 0.0, 1.0);
        return float3(mapped, mapped * mapped, 1.0 - mapped);
    }
    return rrHdr(primeReadonlyImageLoad(rrDebugOutput, fullPixel).rgb);
}

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID, uint groupIndex : SV_GroupIndex) {
    int2 pixel = int2(dispatchThreadId.xy);
    int2 extent = primeImageSize(rrDebugDisplay);
    if (any(pixel >= extent)) return;

    int2 panelOrigin;
    int2 panelExtent;
    if (rrDebugPush.fullscreen != 0u) {
        panelOrigin = int2(0);
        panelExtent = extent;
    } else if (rrDebugPush.view == RR_OVERVIEW) {
        panelExtent = max(int2(360, 260), int2(float2(extent) * float2(0.46, 0.44)));
        panelExtent = min(panelExtent, extent);
        // Prime's Vulkan main target is presented with image row zero at the bottom of the
        // window. Anchor against the opposite image edge so the panel appears top-right.
        panelOrigin = int2(extent.x - panelExtent.x, extent.y - panelExtent.y);
    } else {
        panelExtent = max(int2(300, 180), int2(float2(extent) * float2(0.40, 0.36)));
        panelExtent = min(panelExtent, extent);
        panelOrigin = int2(extent.x - panelExtent.x, extent.y - panelExtent.y);
    }
    if (any(pixel < panelOrigin)
            || any(pixel >= panelOrigin + panelExtent)) return;

    int2 local = pixel - panelOrigin;
    float3 color;
    if (rrDebugPush.view == RR_OVERVIEW) {
        const uint panels[10] = uint[10](
                RR_INPUT_COLOR,
                RR_MOTION,
                RR_SPECULAR_MOTION,
                RR_DEPTH,
                RR_NORMALS,
                RR_ROUGHNESS,
                RR_DIFFUSE_ALBEDO,
                RR_SPECULAR_ALBEDO,
                RR_SPECULAR_HIT_DISTANCE,
                RR_OUTPUT);
        int2 cellExtent = max(panelExtent / int2(5, 2), int2(1));
        int2 cell = clamp(local / cellExtent, int2(0), int2(4, 1));
        uint index = uint(cell.y * 5 + cell.x);
        float2 uv = (float2(local - cell * cellExtent) + 0.5) / float2(cellExtent);
        color = rrPanel(panels[index], uv);
        if (local.x % cellExtent.x < 2 || local.y % cellExtent.y < 2) color = float3(0.85);
    } else {
        float2 uv = (float2(local) + 0.5) / float2(panelExtent);
        color = rrPanel(rrDebugPush.view, uv);
    }
    primeImageStore(rrDebugDisplay, pixel, float4(color, 1.0));
}
