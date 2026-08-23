#language slang 2026
module "phase/hit/shadow_closesthit.slang";

import glsl;

import "contract/trace/shadow_payload.slang";

layout(location = 1) rayPayloadInEXT PrimeShadowPayload payload;

public void primePhase(in BuiltInTriangleIntersectionAttributes attributes)
{
    payload.opticalDepthMomentHitDistance.w = min(RayTCurrent(), 65503.0);
}


