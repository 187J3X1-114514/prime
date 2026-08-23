#language slang 2026
module "service/bsdf/contract/sample.slang";

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

