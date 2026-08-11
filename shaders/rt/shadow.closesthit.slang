import glsl;

#include "rt/shadow_payload.slang"

layout(location = 1) rayPayloadInEXT PrimeShadowPayload payload;

[shader("closesthit")]
void main(
        in BuiltInTriangleIntersectionAttributes attributes)
{
    payload.opticalDepthMomentHitDistance.w = min(RayTCurrent(), 65503.0);
}
