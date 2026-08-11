#ifndef PRIME_NUMERICAL_SLANG
#define PRIME_NUMERICAL_SLANG

static const uint PRIME_NUMERICAL_NAN = 1;
static const uint PRIME_NUMERICAL_POSITIVE_INFINITY = 2;
static const uint PRIME_NUMERICAL_NEGATIVE_INFINITY = 4;
static const uint PRIME_NUMERICAL_FINITE_NEGATIVE = 8;
static const uint PRIME_NUMERICAL_ABOVE_FP16 = 16;
static const uint PRIME_NUMERICAL_ABOVE_UNIT = 128;
static const uint PRIME_NUMERICAL_INVALID_DIRECTION = 256;
static const float PRIME_NUMERICAL_FP16_MAX = 65504.0;
static const float PRIME_NUMERICAL_FP16_DIRECTION_ERROR = 4.0e-3;

static const uint PRIME_NUMERICAL_STAGE_UNSCOPED = 0;
static const uint PRIME_NUMERICAL_STAGE_CAMERA = 1;
static const uint PRIME_NUMERICAL_STAGE_TRACE = 2;
static const uint PRIME_NUMERICAL_STAGE_SURFACE = 3;
static const uint PRIME_NUMERICAL_STAGE_MEDIUM = 4;
static const uint PRIME_NUMERICAL_STAGE_EMISSION = 5;
static const uint PRIME_NUMERICAL_STAGE_DIRECT_LIGHT = 6;
static const uint PRIME_NUMERICAL_STAGE_BSDF_EVALUATE = 7;
static const uint PRIME_NUMERICAL_STAGE_BSDF_SAMPLE = 8;
static const uint PRIME_NUMERICAL_STAGE_PATH_ADVANCE = 9;
static const uint PRIME_NUMERICAL_STAGE_TRANSPARENT_BRANCH = 10;
static const uint PRIME_NUMERICAL_STAGE_GUIDE = 11;
static const uint PRIME_NUMERICAL_STAGE_ACCUMULATION = 12;
static const uint PRIME_NUMERICAL_STAGE_FINAL_OUTPUT = 13;

static const uint PRIME_NUMERICAL_FIELD_NON_FINITE = 1;
static const uint PRIME_NUMERICAL_FIELD_NONNEGATIVE = 2;
static const uint PRIME_NUMERICAL_FIELD_RADIANCE = 3;
static const uint PRIME_NUMERICAL_FIELD_UNIT = 4;
static const uint PRIME_NUMERICAL_FIELD_DIRECTION = 5;
static const uint PRIME_NUMERICAL_FIELD_OUTPUT = 6;

uint primeClassifyNonFinite(float value)
{
    if (isnan(value)) return PRIME_NUMERICAL_NAN;
    if (!isinf(value)) return 0;
    return value > 0.0
            ? PRIME_NUMERICAL_POSITIVE_INFINITY
            : PRIME_NUMERICAL_NEGATIVE_INFINITY;
}

uint primeClassifyNonFinite(float3 value)
{
    return primeClassifyNonFinite(value.x)
            | primeClassifyNonFinite(value.y)
            | primeClassifyNonFinite(value.z);
}

uint primeClassifyNonnegative(float value)
{
    uint flags = primeClassifyNonFinite(value);
    return flags == 0 && value < 0.0
            ? PRIME_NUMERICAL_FINITE_NEGATIVE
            : flags;
}

uint primeClassifyNonnegative(float3 value)
{
    return primeClassifyNonnegative(value.x)
            | primeClassifyNonnegative(value.y)
            | primeClassifyNonnegative(value.z);
}

uint primeClassifyRadiance(float value)
{
    uint flags = primeClassifyNonnegative(value);
    if (flags != 0) return flags;
    if (value > PRIME_NUMERICAL_FP16_MAX) flags |= PRIME_NUMERICAL_ABOVE_FP16;
    return flags;
}

uint primeClassifyRadiance(float3 value)
{
    return primeClassifyRadiance(value.x)
            | primeClassifyRadiance(value.y)
            | primeClassifyRadiance(value.z);
}

uint primeClassifyUnit(float value)
{
    uint flags = primeClassifyNonnegative(value);
    return flags == 0 && value > 1.0
            ? PRIME_NUMERICAL_ABOVE_UNIT
            : flags;
}

uint primeClassifyUnit(float3 value)
{
    return primeClassifyUnit(value.x)
            | primeClassifyUnit(value.y)
            | primeClassifyUnit(value.z);
}

uint primeClassifyDirection(float3 value)
{
    uint flags = primeClassifyNonFinite(value);
    if (flags != 0) return flags;
    float lengthSquared = dot(value, value);
    return isnan(lengthSquared)
            || isinf(lengthSquared)
            || !(lengthSquared > 1.0e-12)
            || abs(lengthSquared - 1.0) > 1.0e-3
            ? PRIME_NUMERICAL_INVALID_DIRECTION
            : 0;
}

uint primeClassifyOptionalDirection(float3 value)
{
    uint flags = primeClassifyNonFinite(value);
    if (flags != 0) return flags;
    return all(value == float3(0.0)) ? 0 : primeClassifyDirection(value);
}

float3 primeRestoreFp16Direction(float3 value)
{
    uint flags = primeClassifyNonFinite(value);
    if (flags != 0) return value;

    float lengthSquared = dot(value, value);
    return lengthSquared > 1.0e-12
                    && abs(lengthSquared - 1.0)
                            <= PRIME_NUMERICAL_FP16_DIRECTION_ERROR
            ? value * rsqrt(lengthSquared)
            : value;
}

#endif
