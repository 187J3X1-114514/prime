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

bool primeWritesNrdShInputs() {
    return (primePush.path.w & PRIME_PATH_SH_INPUT_MASK) != 0u;
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

float primeConfiguredDefaultLinearRoughness() {
    uint encoded = (primePush.path.w >> PRIME_PATH_MATERIAL_ROUGHNESS_SHIFT)
            & PRIME_PATH_MATERIAL_ROUGHNESS_MASK;
    return float(encoded) / PRIME_PATH_MATERIAL_ROUGHNESS_STEPS_PER_UNIT;
}

// default_material.glsl is also compiled by standalone NRD compute passes that have a different
// push-constant ABI. Ray-tracing stages include common.glsl first and therefore replace the
// compile-time reference only where PrimePushConstants is actually available.
#define PRIME_RUNTIME_DEFAULT_LINEAR_ROUGHNESS primeConfiguredDefaultLinearRoughness()

uint primePackOctahedralNormal(vec3 value) {
    vec3 normal = normalize(value);
    normal /= max(abs(normal.x) + abs(normal.y) + abs(normal.z), 1.0e-20);
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * primeSignNotZero(normal.xy);
    }
    return packSnorm2x16(clamp(normal.xy, vec2(-1.0), vec2(1.0)));
}

#endif
