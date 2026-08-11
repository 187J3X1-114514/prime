#language slang 2026
module "bsdf/bsdf_common.slang";

public static const float PRIME_PI = 3.14159265358979323846;

public static const uint PRIME_BSDF_EVENT_REFLECTION = 1 << 0;
public static const uint PRIME_BSDF_EVENT_TRANSMISSION = 1 << 1;
public static const uint PRIME_BSDF_EVENT_DIFFUSE = 1 << 2;
public static const uint PRIME_BSDF_EVENT_GLOSSY = 1 << 3;
public static const uint PRIME_BSDF_EVENT_DISCRETE = 1 << 4;
public static const uint PRIME_MEASURE_SOLID_ANGLE = 1;
public static const uint PRIME_MEASURE_DISCRETE = 2;

public struct PrimeClosureTraits
{
    public uint measureMask;
    public uint eventMask;
};

public bool primeClosureSupports(PrimeClosureTraits traits, uint measure)
{
    return (traits.measureMask & measure) != 0;
}

public bool primeClosureIsDiscreteOnly(PrimeClosureTraits traits)
{
    return traits.measureMask == PRIME_MEASURE_DISCRETE;
}

public struct BsdfEvaluation
{
    public float3 response;
    public float pdf;
};

public struct BsdfSample
{
    public float3 direction;
    public float3 response;
    public float pdf;
    public float relativeEta;
    public uint eventFlags;
};

public struct EdfEvaluation
{
    public float3 radiance;
    public float pdf;
};

public struct EdfSample
{
    public float3 direction;
    public float3 radiance;
    public float pdf;
};

public struct PhaseEvaluation
{
    public float value;
    public float pdf;
};

public struct PhaseSample
{
    public float3 direction;
    public float weight;
    public float pdf;
};

public BsdfEvaluation primeInvalidBsdfEvaluation()
{
    BsdfEvaluation result;
    result.response = float3(0.0);
    result.pdf = 0.0;
    return result;
}

public BsdfSample primeInvalidBsdfSample()
{
    BsdfSample result;
    result.direction = float3(0.0);
    result.response = float3(0.0);
    result.pdf = 0.0;
    result.relativeEta = 1.0;
    result.eventFlags = 0;
    return result;
}

public bool primeBsdfFinite(float value)
{
    return !isnan(value) && !isinf(value);
}

public bool primeBsdfFinite(float3 value)
{
    return !any(isnan(value)) && !any(isinf(value));
}

public bool primeBsdfDirection(float3 value)
{
    if (!primeBsdfFinite(value)) return false;
    float lengthSquared = dot(value, value);
    return primeBsdfFinite(lengthSquared)
            && lengthSquared > 1.0e-12
            && abs(lengthSquared - 1.0) <= 1.0e-3;
}

public BsdfEvaluation primeSanitizeBsdfEvaluation(BsdfEvaluation evaluation)
{
    if (!primeBsdfFinite(evaluation.response)
            || !primeBsdfFinite(evaluation.pdf)
            || any(evaluation.response < float3(0.0))
            || evaluation.pdf < 0.0) {
        return primeInvalidBsdfEvaluation();
    }
    return evaluation;
}

public BsdfSample primeSanitizeBsdfSample(BsdfSample sampleValue)
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
