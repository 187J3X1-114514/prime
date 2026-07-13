#version 460
#extension GL_GOOGLE_include_directive : require
#include "hit_common.glsl"

layout(location = 0) rayPayloadInEXT vec4 primePayload;

void main() {
    PrimitiveRecord primitive = primePrimitive();
    vec4 textureColor = textureLod(primeBlockAtlas, primeInterpolateUv(primitive), 0.0);
    primePayload = textureColor * primeUnpackTint(primitive.tint);
    primePayload.a = 1.0;
}
