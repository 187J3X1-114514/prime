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

float primeStarRadianceMultiplier() {
    uint encoded = (primePush.path.w >> PRIME_PATH_STAR_EV_QUARTER_SHIFT)
            & PRIME_PATH_STAR_EV_QUARTER_MASK;
    int quarterSteps = int(encoded) - PRIME_PATH_STAR_EV_QUARTER_BIAS;
    return exp2(0.25 * float(quarterSteps));
}

// Power-of-two scaling preserves a representable a*b/c when either intermediate operation would
// overflow or underflow. frexp/ldexp transfer exponents exactly; only the significand operations
// and final result round.
float primeProductOver(float first, float second, float denominator) {
    int firstExponent;
    int secondExponent;
    int denominatorExponent;
    float significand = frexp(first, firstExponent)
            * frexp(second, secondExponent)
            / frexp(denominator, denominatorExponent);
    return ldexp(significand, firstExponent + secondExponent - denominatorExponent);
}

vec3 primeProductOver(vec3 first, vec3 second, float denominator) {
    ivec3 firstExponent;
    ivec3 secondExponent;
    int denominatorExponent;
    vec3 significand = frexp(first, firstExponent)
            * frexp(second, secondExponent)
            / frexp(denominator, denominatorExponent);
    return ldexp(
            significand,
            firstExponent + secondExponent - ivec3(denominatorExponent));
}

vec3 primeTripleProduct(vec3 first, vec3 second, float third) {
    ivec3 firstExponent;
    ivec3 secondExponent;
    int thirdExponent;
    vec3 significand = frexp(first, firstExponent)
            * frexp(second, secondExponent)
            * frexp(third, thirdExponent);
    return ldexp(significand, firstExponent + secondExponent + ivec3(thirdExponent));
}

bool primeWritesNrdShInputs() {
    return (primePush.path.w & PRIME_PATH_SH_INPUT_MASK) != 0u;
}

bool primeWritesRawNumericalDiagnostic() {
    return (primePush.path.w & PRIME_PATH_RAW_NUMERICAL_MASK) != 0u;
}

const uint PRIME_NUMERICAL_NAN = 1u;
const uint PRIME_NUMERICAL_POSITIVE_INFINITY = 2u;
const uint PRIME_NUMERICAL_NEGATIVE_INFINITY = 4u;
const uint PRIME_NUMERICAL_FINITE_NEGATIVE = 8u;
const uint PRIME_NUMERICAL_ABOVE_FP16 = 16u;
const uint PRIME_NUMERICAL_ABOVE_UNIT = 128u;
const uint PRIME_NUMERICAL_INVALID_DIRECTION = 256u;
const float PRIME_NUMERICAL_FP16_MAX = 65504.0;

uint primeRawNumericalFlags = 0u;

uint primeClassifyNonFinite(float value) {
    if (isnan(value)) return PRIME_NUMERICAL_NAN;
    if (!isinf(value)) return 0u;
    return value > 0.0
            ? PRIME_NUMERICAL_POSITIVE_INFINITY
            : PRIME_NUMERICAL_NEGATIVE_INFINITY;
}

uint primeClassifyNonFinite(vec3 value) {
    return primeClassifyNonFinite(value.x)
            | primeClassifyNonFinite(value.y)
            | primeClassifyNonFinite(value.z);
}

uint primeClassifyNonnegative(float value) {
    uint flags = primeClassifyNonFinite(value);
    return flags == 0u && value < 0.0
            ? PRIME_NUMERICAL_FINITE_NEGATIVE
            : flags;
}

uint primeClassifyNonnegative(vec3 value) {
    return primeClassifyNonnegative(value.x)
            | primeClassifyNonnegative(value.y)
            | primeClassifyNonnegative(value.z);
}

uint primeClassifyRadiance(float value) {
    uint flags = primeClassifyNonnegative(value);
    if (flags != 0u) return flags;
    if (value > PRIME_NUMERICAL_FP16_MAX) flags |= PRIME_NUMERICAL_ABOVE_FP16;
    return flags;
}

uint primeClassifyRadiance(vec3 value) {
    return primeClassifyRadiance(value.x)
            | primeClassifyRadiance(value.y)
            | primeClassifyRadiance(value.z);
}

uint primeClassifyUnit(float value) {
    uint flags = primeClassifyNonnegative(value);
    return flags == 0u && value > 1.0
            ? PRIME_NUMERICAL_ABOVE_UNIT
            : flags;
}

uint primeClassifyUnit(vec3 value) {
    return primeClassifyUnit(value.x)
            | primeClassifyUnit(value.y)
            | primeClassifyUnit(value.z);
}

uint primeClassifyDirection(vec3 value) {
    uint flags = primeClassifyNonFinite(value);
    if (flags != 0u) return flags;
    float lengthSquared = dot(value, value);
    return isnan(lengthSquared)
            || isinf(lengthSquared)
            || !(lengthSquared > 1.0e-12)
            || abs(lengthSquared - 1.0) > 1.0e-3
            ? PRIME_NUMERICAL_INVALID_DIRECTION
            : 0u;
}

void primeRecordNonFinite(float value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRawNumericalFlags |= primeClassifyNonFinite(value);
    }
}

void primeRecordNonFinite(vec3 value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRawNumericalFlags |= primeClassifyNonFinite(value);
    }
}

void primeRecordNonnegative(float value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRawNumericalFlags |= primeClassifyNonnegative(value);
    }
}

void primeRecordNonnegative(vec3 value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRawNumericalFlags |= primeClassifyNonnegative(value);
    }
}

void primeRecordRadiance(vec3 value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRawNumericalFlags |= primeClassifyRadiance(value);
    }
}

void primeRecordUnit(float value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRawNumericalFlags |= primeClassifyUnit(value);
    }
}

void primeRecordUnit(vec3 value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRawNumericalFlags |= primeClassifyUnit(value);
    }
}

void primeRecordDirection(vec3 value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRawNumericalFlags |= primeClassifyDirection(value);
    }
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
