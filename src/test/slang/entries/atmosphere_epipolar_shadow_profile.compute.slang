
#include "core/sun_shadow_hierarchy.slang"



static const int SUN_SHADOW_CASCADE_COUNT = 5;
static const float SUN_SHADOW_MAX_DISTANCE_METERS = 2048.0;
static const float SUN_SHADOW_DEPTH_BIAS_METERS = 0.02;
static const float SUN_SHADOW_CASCADE_RADIUS_TEXELS = 254.0;
static const float SUN_SHADOW_TRAVERSAL_EPSILON = 1.0e-5;

struct PrimeProfileCase {
    float4 cameraDirection;
    float4 rayBlocker;
    float4 intervalDepth;
};

[[vk::binding(0, 0)]] StructuredBuffer<PrimeProfileCase> primeProfileInput;

[[vk::binding(1, 0)]] RWStructuredBuffer<float> primeProfileOutput;

struct PrimeTestAtmospherePush {
    float4 shadowCameraEnabled;
};

static const PrimeTestAtmospherePush atmospherePush =
        PrimeTestAtmospherePush(float4(0.0, 0.0, 0.0, 1.0));
groupshared uint primeProfileCaseIndex;

float sunShadowTexelSize(int cascade) {
    return cascade == 0 ? 0.5
            : (cascade == 1 ? 1.0
            : (cascade == 2 ? 2.0
            : (cascade == 3 ? 4.0 : 8.0)));
}

float2 sunShadowHierarchyRange(
        int cascade, int level, int2 worldNode) {
    float blocker = primeProfileInput
            [primeProfileCaseIndex]
            .rayBlocker.w;
    return float2(blocker);
}

#include "core/atmosphere_epipolar_shadow.slang"

[numthreads(128, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID, uint3 groupThreadId : SV_GroupThreadID, uint3 groupId : SV_GroupID, uint groupIndex : SV_GroupIndex) {
    uint caseIndex = groupId.x;
    PrimeProfileCase value = primeProfileInput[caseIndex];
    if (groupIndex == 0) {
        primeProfileCaseIndex = caseIndex;
        sunShadowProfileBasisU = float3(1.0, 0.0, 0.0);
        sunShadowProfileBasisV = float3(0.0, 1.0, 0.0);
        sunShadowProfileDirectionToSun = float3(0.0, 0.0, 1.0);
        sunShadowProfileCamera = value.cameraDirection.xy;
        sunShadowProfileDirection = normalize(value.cameraDirection.zw);
        sunShadowProfileDirectionMax = max(
                abs(sunShadowProfileDirection.x),
                abs(sunShadowProfileDirection.y));
        sunShadowProfileCameraDepth = value.intervalDepth.z;
    }
    GroupMemoryBarrierWithGroupSync();
    if (groupIndex < SUN_SHADOW_CASCADE_COUNT) {
        sunShadowBuildProfileCascade(int(groupIndex));
    }
    GroupMemoryBarrierWithGroupSync();
    if (groupIndex == 0) {
        int outputBase = int(caseIndex) * 7;
        for (int cascade = 0;
                cascade < SUN_SHADOW_CASCADE_COUNT;
                ++cascade) {
            primeProfileOutput[outputBase + cascade] =
                    float(sunShadowProfileCounts[cascade]);
        }
        int last = sunShadowProfileCounts[0] - 1;
        primeProfileOutput[outputBase + 5] = last >= 0
                ? sunShadowProfileEntries[last].x
                : -1.0;
        primeProfileOutput[outputBase + 6] =
                localSunVisibilityInterval(
                        value.rayBlocker.xyz,
                        value.intervalDepth.x,
                        value.intervalDepth.y);
    }
}
