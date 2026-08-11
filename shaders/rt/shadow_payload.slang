#language slang 2026
module "rt/shadow_payload.slang";

public struct PrimeShadowPayload
{
    public float4 opticalDepthMomentHitDistance;
    public float4 terminalExtinctionRayDistance;
    public float4 startingExtinction0Winding;
    public float4 startingExtinction1Winding;
    public uint startingMediumCount;
};
