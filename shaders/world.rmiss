#version 460
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

layout(location = 0) rayPayloadInEXT vec4 primePayload;

void main() {
    primePayload = vec4(0.0, 0.0, 0.0, 1.0);
}
