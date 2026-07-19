#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 1) rayPayloadInEXT uint primeShadowHitDistanceBits;

void main() {
    primeShadowHitDistanceBits = floatBitsToUint(65504.0);
}
