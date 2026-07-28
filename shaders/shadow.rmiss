#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_GOOGLE_include_directive : require

#include "shadow_payload.glsl"

layout(location = 1) rayPayloadInEXT PrimeShadowPayload primeShadowPayload;

void main() {
    primeShadowPayload.hitDistance = 65504.0;
}
