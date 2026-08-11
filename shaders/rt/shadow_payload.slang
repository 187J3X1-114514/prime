#ifndef PRIME_SHADOW_PAYLOAD_SLANG
#define PRIME_SHADOW_PAYLOAD_SLANG

struct PrimeShadowPayload
{
    float4 opticalDepthMomentHitDistance;
    float4 terminalExtinctionRayDistance;
    float4 startingExtinction0Winding;
    float4 startingExtinction1Winding;
    uint startingMediumCount;
};

#endif
