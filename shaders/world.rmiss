#version 460
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

void main() {
    primePayload.hitKind = 0u;
}
