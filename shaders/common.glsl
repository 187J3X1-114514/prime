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

#endif
