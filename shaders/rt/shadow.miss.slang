import glsl;

#include "rt/shadow_payload.slang"

layout(location = 1) rayPayloadInEXT PrimeShadowPayload payload;

[shader("miss")]
void main()
{
    payload.opticalDepthMomentHitDistance.w = 65504.0;
}
