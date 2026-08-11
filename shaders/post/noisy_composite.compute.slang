#include "core/image_access.slang"

#include "core/atmosphere.slang"
#include "core/atmosphere_epipolar.slang"
#include "core/nrd_common.slang"

[[vk::binding(0, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeNoisyDiffuse;
[[vk::binding(1, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeNoisySpecular;
[[vk::binding(2, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeNoisyMaterial;
[[vk::binding(3, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> primeNoisyStableRadiance;
[[vk::binding(4, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> primeNoisySunLighting;
[[vk::binding(5, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture3D<float4> primeNoisyAerialRadiance;
[[vk::binding(6, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture3D<float4> primeNoisyAerialTransmittance;
[[vk::binding(7, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> primeNoisyOutput;

struct PrimeNoisyCompositePushConstants {
    uint2 outputExtent;
    float sunRadianceMultiplier;
    float padding;
    float2 epipoleNdc;
};
[[vk::push_constant]] ConstantBuffer<PrimeNoisyCompositePushConstants> primeNoisyPush;

ATM_DECLARE_SAMPLE_EPIPOLAR(
        primeNoisySampleAerialRadiance,
        primeNoisyAerialRadiance)
ATM_DECLARE_SAMPLE_3D(primeNoisySampleAerialTransmittance, primeNoisyAerialTransmittance)
// The viewport corner angles are identical for every lane in this 8x8 workgroup.
groupshared AtmEpipolarRange primeNoisyEpipolarRange;

void primeNoisyApplyAerial(uint2 pixel, float primaryDistance, inout float3 radiance) {
    if (!primeNrdIsFinite(primaryDistance) || primaryDistance < 0.0) return;
    float distanceKm = primaryDistance * ATM_WORLD_UNIT_SCALE_KM;
    int3 dimensions = primeReadonlyImageSize(primeNoisyAerialRadiance);
    float firstSliceDepth = 0.5 / float(max(dimensions.z, 1));
    float firstSliceDistanceKm = ATM_AERIAL_MAX_DISTANCE_KM
            * firstSliceDepth * firstSliceDepth;
    float normalizedDepth = max(firstSliceDepth, sqrt(clamp(
            distanceKm / max(ATM_AERIAL_MAX_DISTANCE_KM, 1.0e-6), 0.0, 1.0)));
    float nearWeight = clamp(
            distanceKm / max(firstSliceDistanceKm, 1.0e-6), 0.0, 1.0);
    float2 screenUv = (float2(pixel) + float2(0.5)) / float2(primeNoisyPush.outputExtent);
    float3 uvw = float3(screenUv, normalizedDepth);
    float3 inscatter = max(
            primeNoisySampleAerialRadianceWithRange(
                    screenUv,
                    normalizedDepth,
                    primeNoisyPush.epipoleNdc,
                    primeNoisyEpipolarRange).rgb,
            float3(0.0));
    float3 transmittance = clamp(
            primeNoisySampleAerialTransmittance(uvw).rgb, float3(0.0), float3(1.0));
    if (any(isnan(inscatter)) || any(isinf(inscatter))) inscatter = float3(0.0);
    if (any(isnan(transmittance)) || any(isinf(transmittance))) {
        transmittance = float3(1.0);
    }
    radiance = radiance * lerp(float3(1.0), transmittance, nearWeight)
            + inscatter * nearWeight * primeNoisyPush.sunRadianceMultiplier;
}

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID, uint groupIndex : SV_GroupIndex) {
    if (groupIndex == 0u) {
        primeNoisyEpipolarRange = atmEpipolarVisibleRange(
                primeNoisyPush.epipoleNdc);
    }
    GroupMemoryBarrierWithGroupSync();
    uint2 pixel = dispatchThreadId.xy;
    if (any(pixel >= primeNoisyPush.outputExtent)) return;

    float4 material = primeReadonlyImageLoad(primeNoisyMaterial, int2(pixel));
    if (!primeNrdIsFinite(material.a) || material.a < 0.0) {
        primeWriteImageStore(
                primeNoisyOutput,
                int2(pixel),
                float4(primeNrdClampRadiance(
                        primeReadonlyImageLoad(primeNoisyStableRadiance, int2(pixel)).rgb,
                        PRIME_NRD_FP16_MAX), 1.0));
        return;
    }
    float4 sun = primeReadonlyImageLoad(primeNoisySunLighting, int2(pixel));
    float3 surface = primeNrdSanitizeRadiance(
            primeReadonlyImageLoad(primeNoisyDiffuse, int2(pixel)).rgb)
            + primeNrdSanitizeRadiance(
                    primeReadonlyImageLoad(primeNoisySpecular, int2(pixel)).rgb)
            + primeNrdSanitizeRadiance(sun.rgb)
                    * primeNrdSanitizeUnit(sun.a, 0.0);
    float3 stable = primeNrdClampRadiance(
            primeReadonlyImageLoad(primeNoisyStableRadiance, int2(pixel)).rgb,
            PRIME_NRD_FP16_MAX);
    float3 radiance = surface + stable;
    primeNoisyApplyAerial(pixel, material.a, radiance);
    primeWriteImageStore(
            primeNoisyOutput,
            int2(pixel),
            float4(primeNrdClampRadiance(radiance, PRIME_NRD_FP16_MAX), 1.0));
}
