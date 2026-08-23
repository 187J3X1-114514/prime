#language slang 2026
module "phase/hit/shadow_miss.slang";

import glsl;

import "contract/trace/shadow_payload.slang";

layout(location = 1) rayPayloadInEXT PrimeShadowPayload payload;

public void primePhase()
{
    payload.opticalDepthMomentHitDistance.w = 65504.0;
}


