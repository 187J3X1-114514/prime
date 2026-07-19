#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 1) rayPayloadInEXT uint primeShadowHitDistanceBits;

void main() {
    // Keep accepted hits distinguishable from SIGMA's 65504 miss sentinel even at the far edge
    // of Prime's denoising range.
    primeShadowHitDistanceBits = floatBitsToUint(min(gl_HitTEXT, 65503.0));
}
