#ifndef PRIME_COMMON_GLSL
#define PRIME_COMMON_GLSL

#extension GL_GOOGLE_include_directive : require
#include "prime_abi.glsl"

vec2 primeUnpackHalf2(uint packedValue) {
    return unpackHalf2x16(packedValue);
}

vec4 primeUnpackTint(uint packedValue) {
    return unpackUnorm4x8(packedValue);
}

vec2 primeSignNotZero(vec2 value) {
    return vec2(value.x >= 0.0 ? 1.0 : -1.0, value.y >= 0.0 ? 1.0 : -1.0);
}

vec3 primeUnpackOctahedralNormal(uint packedValue) {
    vec2 encoded = unpackSnorm2x16(packedValue);
    vec3 normal = vec3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * primeSignNotZero(normal.xy);
    }
    return normalize(normal);
}

#endif
