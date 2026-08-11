#ifndef PRIME_BSDF_COMMON_SLANG
#define PRIME_BSDF_COMMON_SLANG

static const float PRIME_PI = 3.14159265358979323846;

static const uint PRIME_BSDF_EVENT_REFLECTION = 1 << 0;
static const uint PRIME_BSDF_EVENT_TRANSMISSION = 1 << 1;
static const uint PRIME_BSDF_EVENT_DIFFUSE = 1 << 2;
static const uint PRIME_BSDF_EVENT_GLOSSY = 1 << 3;
static const uint PRIME_BSDF_EVENT_DISCRETE = 1 << 4;
static const uint PRIME_MEASURE_SOLID_ANGLE = 1;
static const uint PRIME_MEASURE_DISCRETE = 2;

struct PrimeClosureTraits
{
    uint measureMask;
    uint eventMask;
};

bool primeClosureSupports(PrimeClosureTraits traits, uint measure)
{
    return (traits.measureMask & measure) != 0;
}

bool primeClosureIsDiscreteOnly(PrimeClosureTraits traits)
{
    return traits.measureMask == PRIME_MEASURE_DISCRETE;
}

struct BsdfEvaluation
{
    float3 response;
    float pdf;
};

struct BsdfSample
{
    float3 direction;
    float3 response;
    float pdf;
    float relativeEta;
    uint eventFlags;
};

struct EdfEvaluation
{
    float3 radiance;
    float pdf;
};

struct EdfSample
{
    float3 direction;
    float3 radiance;
    float pdf;
};

struct PhaseEvaluation
{
    float value;
    float pdf;
};

struct PhaseSample
{
    float3 direction;
    float weight;
    float pdf;
};

BsdfEvaluation primeInvalidBsdfEvaluation()
{
    BsdfEvaluation result;
    result.response = float3(0.0);
    result.pdf = 0.0;
    return result;
}

BsdfSample primeInvalidBsdfSample()
{
    BsdfSample result;
    result.direction = float3(0.0);
    result.response = float3(0.0);
    result.pdf = 0.0;
    result.relativeEta = 1.0;
    result.eventFlags = 0;
    return result;
}

bool primeBsdfFinite(float value)
{
    return !isnan(value) && !isinf(value);
}

bool primeBsdfFinite(float3 value)
{
    return !any(isnan(value)) && !any(isinf(value));
}

bool primeBsdfDirection(float3 value)
{
    if (!primeBsdfFinite(value)) return false;
    float lengthSquared = dot(value, value);
    return primeBsdfFinite(lengthSquared)
            && lengthSquared > 1.0e-12
            && abs(lengthSquared - 1.0) <= 1.0e-3;
}

BsdfEvaluation primeSanitizeBsdfEvaluation(BsdfEvaluation evaluation)
{
    if (!primeBsdfFinite(evaluation.response)
            || !primeBsdfFinite(evaluation.pdf)
            || any(evaluation.response < float3(0.0))
            || evaluation.pdf < 0.0) {
        return primeInvalidBsdfEvaluation();
    }
    return evaluation;
}

BsdfSample primeSanitizeBsdfSample(BsdfSample sampleValue)
{
    if (sampleValue.eventFlags == 0) {
        return primeInvalidBsdfSample();
    }
    if (!primeBsdfDirection(sampleValue.direction)
            || !primeBsdfFinite(sampleValue.response)
            || !primeBsdfFinite(sampleValue.pdf)
            || !primeBsdfFinite(sampleValue.relativeEta)
            || any(sampleValue.response < float3(0.0))
            || !(sampleValue.pdf > 0.0)
            || !(sampleValue.relativeEta > 0.0)) {
        return primeInvalidBsdfSample();
    }
    return sampleValue;
}

#endif
