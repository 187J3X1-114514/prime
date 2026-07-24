#ifndef PRIME_NUMERICAL_GLSL
#define PRIME_NUMERICAL_GLSL

// Shared numerical contracts. Keep classification independent of ray-tracing resources so
// compute property tests and diagnostic presentation execute the production definitions.
const uint PRIME_NUMERICAL_NAN = 1u;
const uint PRIME_NUMERICAL_POSITIVE_INFINITY = 2u;
const uint PRIME_NUMERICAL_NEGATIVE_INFINITY = 4u;
const uint PRIME_NUMERICAL_FINITE_NEGATIVE = 8u;
const uint PRIME_NUMERICAL_ABOVE_FP16 = 16u;
const uint PRIME_NUMERICAL_ABOVE_UNIT = 128u;
const uint PRIME_NUMERICAL_INVALID_DIRECTION = 256u;
const float PRIME_NUMERICAL_FP16_MAX = 65504.0;

const uint PRIME_NUMERICAL_STAGE_UNSCOPED = 0u;
const uint PRIME_NUMERICAL_STAGE_CAMERA = 1u;
const uint PRIME_NUMERICAL_STAGE_TRACE = 2u;
const uint PRIME_NUMERICAL_STAGE_SURFACE = 3u;
const uint PRIME_NUMERICAL_STAGE_MEDIUM = 4u;
const uint PRIME_NUMERICAL_STAGE_EMISSION = 5u;
const uint PRIME_NUMERICAL_STAGE_DIRECT_LIGHT = 6u;
const uint PRIME_NUMERICAL_STAGE_BSDF_EVALUATE = 7u;
const uint PRIME_NUMERICAL_STAGE_BSDF_SAMPLE = 8u;
const uint PRIME_NUMERICAL_STAGE_PATH_ADVANCE = 9u;
const uint PRIME_NUMERICAL_STAGE_TRANSPARENT_BRANCH = 10u;
const uint PRIME_NUMERICAL_STAGE_GUIDE = 11u;
const uint PRIME_NUMERICAL_STAGE_ACCUMULATION = 12u;
const uint PRIME_NUMERICAL_STAGE_FINAL_OUTPUT = 13u;

const uint PRIME_NUMERICAL_FIELD_NON_FINITE = 1u;
const uint PRIME_NUMERICAL_FIELD_NONNEGATIVE = 2u;
const uint PRIME_NUMERICAL_FIELD_RADIANCE = 3u;
const uint PRIME_NUMERICAL_FIELD_UNIT = 4u;
const uint PRIME_NUMERICAL_FIELD_DIRECTION = 5u;
const uint PRIME_NUMERICAL_FIELD_OUTPUT = 6u;

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

#endif
