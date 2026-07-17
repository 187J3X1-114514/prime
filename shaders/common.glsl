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

int primeDecodeEvQuarterSteps(uint shift) {
    uint encoded = (primePush.path.w >> shift) & PRIME_PATH_EV_QUARTER_MASK;
    return int(encoded) - PRIME_PATH_EV_QUARTER_BIAS;
}

float primeSunRadianceMultiplier() {
    return exp2(0.25 * float(primeDecodeEvQuarterSteps(PRIME_PATH_SUN_EV_QUARTER_SHIFT)));
}

float primeBlockLightRadianceMultiplier() {
    return exp2(0.25 * float(primeDecodeEvQuarterSteps(
            PRIME_PATH_BLOCK_LIGHT_EV_QUARTER_SHIFT)));
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
