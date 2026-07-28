#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_GOOGLE_include_directive : require

#include "shadow_payload.glsl"

layout(location = 1) rayPayloadInEXT PrimeShadowPayload primeShadowPayload;

void main() {
    // Keep accepted hits distinguishable from SIGMA's 65504 miss sentinel even at the far edge
    // of Prime's denoising range.
    primeShadowPayload.hitDistance = min(gl_HitTEXT, 65503.0);
}
