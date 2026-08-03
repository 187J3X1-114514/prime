#ifndef PRIME_COMMON_GLSL
#define PRIME_COMMON_GLSL

#extension GL_GOOGLE_include_directive : require
#include "prime_abi.glsl"
#include "numerical.glsl"
#include "transport_math.glsl"

vec2 primeUnpackHalf2(uint packedValue) {
    return unpackHalf2x16(packedValue);
}

vec4 primeUnpackTint(uint packedValue) {
    return unpackUnorm4x8(packedValue);
}

uint primeSampleIndex() {
    return primePush.path.x & PRIME_PATH_SAMPLE_INDEX_MASK;
}

float primeSolarLongitudeRadians() {
    uint encoded = (primePush.path.x >> PRIME_PATH_SOLAR_LONGITUDE_SHIFT)
            & PRIME_PATH_SOLAR_LONGITUDE_MASK;
    return radians(float(encoded));
}

float primeObserverLatitudeRadians() {
    uint encoded = (primePush.path.z >> PRIME_PATH_LATITUDE_SHIFT)
            & PRIME_PATH_LATITUDE_MASK;
    return radians(float(int(encoded) - PRIME_PATH_LATITUDE_BIAS));
}

uint primeMaximumBounces() {
    return min(
            primePush.path.z & PRIME_PATH_MAXIMUM_BOUNCES_MASK,
            PRIME_MAXIMUM_BOUNCES);
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

bool primeWritesNrdShInputs() {
    return (primePush.path.w & PRIME_PATH_SH_INPUT_MASK) != 0u;
}

bool primeWritesRawNumericalDiagnostic() {
    return (primePush.path.w & PRIME_PATH_RAW_NUMERICAL_MASK) != 0u;
}

uint primeSampleEpoch() {
    return primePush.path.y & PRIME_PATH_SAMPLE_EPOCH_MASK;
}

bool primeVisualizesTriangles() {
    return (primePush.path.y & PRIME_PATH_TRIANGLE_DEBUG_MASK) != 0u;
}

uint primeRawNumericalFlags = 0u;
uint primeRawNumericalContext = 0u;
uint primeRawNumericalFirstContext = 0u;

void primeSetNumericalContext(uint stage, uint bounce) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRawNumericalContext = (stage & 0x0fu) | (min(bounce, 255u) << 4u);
    }
}

void primeRecordNumerical(uint flags, uint field) {
    if (flags == 0u) return;
    if (primeRawNumericalFirstContext == 0u) {
        // Add one so zero remains the unrecorded sentinel. Stage and bounce occupy exactly
        // representable integer ranges in the RGBA16F diagnostic target.
        primeRawNumericalFirstContext =
                1u + primeRawNumericalContext + ((field & 0x0fu) << 12u);
    }
    primeRawNumericalFlags |= flags;
}

vec4 primeRawNumericalMetadata() {
    if (primeRawNumericalFlags == 0u) return vec4(0.0);
    uint context = primeRawNumericalFirstContext - 1u;
    return vec4(
            float(primeRawNumericalFlags),
            float(context & 0x0fu),
            float((context >> 4u) & 0xffu),
            float((context >> 12u) & 0x0fu));
}

void primeRecordNonFinite(float value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                primeClassifyNonFinite(value), PRIME_NUMERICAL_FIELD_NON_FINITE);
    }
}

void primeRecordNonFinite(vec3 value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                primeClassifyNonFinite(value), PRIME_NUMERICAL_FIELD_NON_FINITE);
    }
}

void primeRecordNonnegative(float value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                primeClassifyNonnegative(value), PRIME_NUMERICAL_FIELD_NONNEGATIVE);
    }
}

void primeRecordNonnegative(vec3 value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                primeClassifyNonnegative(value), PRIME_NUMERICAL_FIELD_NONNEGATIVE);
    }
}

void primeRecordRadiance(vec3 value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                primeClassifyRadiance(value), PRIME_NUMERICAL_FIELD_RADIANCE);
    }
}

void primeRecordUnit(float value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                primeClassifyUnit(value), PRIME_NUMERICAL_FIELD_UNIT);
    }
}

void primeRecordUnit(vec3 value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                primeClassifyUnit(value), PRIME_NUMERICAL_FIELD_UNIT);
    }
}

void primeRecordDirection(vec3 value) {
    if (primeWritesRawNumericalDiagnostic()) {
        primeRecordNumerical(
                primeClassifyDirection(value), PRIME_NUMERICAL_FIELD_DIRECTION);
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
