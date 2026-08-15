#language slang 2026
module "bsdf/compact/openpbr_microfacet.slang";

import "bsdf/compact/openpbr_common.slang";
import "bsdf/core/transmission_ggx_energy.slang";

// Exact production-reachable microfacet subset. Generic full-state
// refractive entry points remain only in the RoboCute test oracle.

public bool primeOpenPbrMicrofacetEffectivelySmooth(PrimeOpenPbrMicrofacet microfacet) {
    return primeOpenPbrReduceMax(microfacet.alpha) < 1.0e-4;
}

public float primeOpenPbrMicrofacetLambda(PrimeOpenPbrMicrofacet microfacet, float3 value) {
    return (sqrt(1.0 + primeOpenPbrReduceSum(primeOpenPbrSquare(microfacet.alpha * value.xy))
            / primeOpenPbrSquare(value.z)) - sign(value.z)) * 0.5;
}

public float primeOpenPbrMicrofacetD(PrimeOpenPbrMicrofacet microfacet, float3 halfVector) {
    float denominator = primeOpenPbrReduceSum(primeOpenPbrSquare(halfVector.xy / microfacet.alpha))
            + primeOpenPbrSquare(halfVector.z);
    float result = 1.0 / (PRIME_OPENPBR_PI * microfacet.alpha.x * microfacet.alpha.y
            * primeOpenPbrSquare(denominator));
    return result * halfVector.z > 1.0e-20 ? result : 0.0;
}

float primeOpenPbrMicrofacetG2r(PrimeOpenPbrMicrofacet microfacet, float3 wi, float3 wo) {
    if (wi.z < 0.0) {
        wi = -wi;
        wo = -wo;
    }
    return 1.0 / (primeOpenPbrMicrofacetLambda(microfacet, -wi)
            + primeOpenPbrMicrofacetLambda(microfacet, wo));
}

float primeOpenPbrLogGamma(float value) {
    const float gam0 = 1.0 / 12.0;
    const float gam1 = 1.0 / 30.0;
    const float gam2 = 53.0 / 210.0;
    const float gam3 = 195.0 / 371.0;
    const float gam4 = 22999.0 / 22737.0;
    const float gam5 = 29944523.0 / 19733142.0;
    const float gam6 = 109535241009.0 / 48264275462.0;
    return 0.5 * log(2.0 * PRIME_OPENPBR_PI) - value + (value - 0.5) * log(value)
            + gam0 / (value + gam1 / (value + gam2 / (value + gam3
            / (value + gam4 / (value + gam5 / (value + gam6 / value))))));
}

float primeOpenPbrGamma(float value) {
    return exp(primeOpenPbrLogGamma(value + 5.0))
            / (value * (value + 1.0) * (value + 2.0) * (value + 3.0) * (value + 4.0));
}

float primeOpenPbrBeta(float first, float second) {
    return primeOpenPbrGamma(first) * primeOpenPbrGamma(second) / primeOpenPbrGamma(first + second);
}

public float primeOpenPbrMicrofacetG2t(PrimeOpenPbrMicrofacet microfacet, float3 wi, float3 wo) {
    if (wi.z < 0.0) {
        wi = -wi;
        wo = -wo;
    }
    return primeOpenPbrBeta(
            primeOpenPbrMicrofacetLambda(microfacet, -wi),
            primeOpenPbrMicrofacetLambda(microfacet, wo));
}

public float3 primeOpenPbrMicrofacetSample(
        PrimeOpenPbrMicrofacet microfacet,
        float3 wi,
        float2 sampleValue,
        bool reflectOnly) {
    float3 visibleHalf = normalize(float3(microfacet.alpha * wi.xy, wi.z));
    float phi = 2.0 * PRIME_OPENPBR_PI * sampleValue.x;
    float k = 1.0;
    if (reflectOnly) {
        float a = clamp(min(microfacet.alpha.x, microfacet.alpha.y), 0.0, 1.0);
        float s = 1.0 + length(wi.xy);
        float a2 = a * a;
        float s2 = s * s;
        k = (s2 - a2 * s2) / (s2 + a2 * wi.z * wi.z);
    }
    float z = lerp(1.0, -k * visibleHalf.z, sampleValue.y);
    float sine = sqrt(clamp(1.0 - z * z, 0.0, 1.0));
    float3 halfVector = float3(sine * cos(phi), sine * sin(phi), z) + visibleHalf;
    return normalize(float3(microfacet.alpha * halfVector.xy, max(0.0, halfVector.z)));
}

public float primeOpenPbrMicrofacetPdf(
        PrimeOpenPbrMicrofacet microfacet,
        float3 wi,
        float3 halfVector,
        bool reflectOnly) {
    float lengthV = length(float3(microfacet.alpha * wi.xy, wi.z));
    float k = 1.0;
    if (reflectOnly) {
        float a = clamp(min(microfacet.alpha.x, microfacet.alpha.y), 0.0, 1.0);
        float s = 1.0 + length(wi.xy);
        float a2 = a * a;
        float s2 = s * s;
        k = (s2 - a2 * s2) / (s2 + a2 * wi.z * wi.z);
    }
    return 2.0 * primeOpenPbrMicrofacetD(microfacet, halfVector) * dot(wi, halfVector)
            / (k * wi.z + lengthV);
}

float primeOpenPbrMicrofacetAlpha2(PrimeOpenPbrMicrofacet microfacet) {
    return 0.5 * primeOpenPbrReduceSum(primeOpenPbrSquare(microfacet.alpha));
}

public float3 primeOpenPbrMicrofacetDirectionalAlbedoReflection(
        PrimeOpenPbrMicrofacet microfacet,
        float cosineTheta,
        float3 f0,
        float3 f90) {
    float x = cosineTheta;
    float y2 = primeOpenPbrMicrofacetAlpha2(microfacet);
    float x2 = x * x;
    float y = sqrt(y2);
    float4 r = float4(0.1003, 0.9345, 1.0, 1.0)
            + float4(-0.6303, -2.323, -1.765, 0.2281) * x
            + float4(9.748, 2.229, 8.263, 15.94) * y
            + float4(-2.038, -3.748, 11.53, -55.83) * x * y
            + float4(29.34, 1.424, 28.96, 13.08) * x2
            + float4(-8.245, -0.7684, -7.507, 41.26) * y2
            + float4(-26.44, 1.436, -36.11, 54.9) * x2 * y
            + float4(19.99, 0.2913, 15.86, 300.2) * x * y2
            + float4(-5.448, 0.6286, 33.37, -285.1) * x2 * y2;
    float2 ab = clamp(r.xy / r.zw, float2(0.0), float2(1.0));
    return f0 * ab.x + f90 * ab.y;
}

public float3 primeOpenPbrMicrofacetDielectricMsCompensation<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        PrimeOpenPbrMicrofacet microfacet,
        float cosineTheta,
        float ior)
    where TransmissionGgxEnergy : IPrimeRcTransmissionGgxEnergy
{
    if (ior == 1.0) { return float3(1.0, 1.0, 0.0); }

    const float qMin = 5.960464477539063e-8;
    const float qCritical = 0.00010002000500139488;
    const float qMax = 0.999499874937461;
    const float nearLog2Scale = 0.09334822745469708;
    const float farLog2Min = -13.287279491668832;
    const float farLog2Scale = 0.04123374454764629;

    bool inside = (cosineTheta < 0.0) != (ior < 1.0);
    float mu = clamp(abs(cosineTheta), 0.02, 1.0);
    float q = clamp(abs((ior - 1.0) / (ior + 1.0)), qMin, qMax);
    float critical = min(2.0 * sqrt(q) / (1.0 + q), 0.9999999403953552);
    bool nearInside = inside && q <= qCritical;
    bool belowCritical = inside && !nearInside && mu < critical;

    float nearU = clamp((log2(q) + 24.0) * nearLog2Scale, 0.0, 1.0);
    float nearZ = nearU - 0.65 * nearU * (1.0 - nearU) * (2.0 * nearU - 1.0);
    float farU = clamp((log2(q / (1.0 - q)) - farLog2Min) * farLog2Scale, 0.0, 1.0);
    float farZ = sqrt(farU);
    float exteriorZ = q <= qCritical
            ? (12.0 / 47.0) * nearZ
            : (12.0 + 35.0 * farZ) / 47.0;
    float logicalZ = inside ? (nearInside ? nearZ : farZ) : exteriorZ;
    float zOffset = inside ? (nearInside ? 48.0 : (belowCritical ? 111.0 : 63.0)) : 0.0;
    float zExtent = nearInside ? 15.0 : 47.0;

    float roughness = sqrt(sqrt(primeOpenPbrMicrofacetAlpha2(microfacet)));
    float roughnessCoordinate = sqrt(clamp((roughness - 0.01) / 0.99, 0.0, 1.0));
    float linearView = (mu - 0.02) / 0.98;
    float belowView = (mu - 0.02) / max(critical - 0.02, 1.0e-20);
    float aboveView = (mu - critical) / max(1.0 - critical, 1.0e-20);
    float view = clamp(
            belowCritical ? belowView : (inside && !nearInside ? aboveView : linearView),
            0.0,
            1.0);
    view = view <= 0.5
            ? 0.5 * sqrt(2.0 * view)
            : 1.0 - 0.5 * sqrt(2.0 * (1.0 - view));
    view -= 0.64 * view * (1.0 - view) * (2.0 * view - 1.0);

    float3 uvw = float3(
            (view * 43.0 + 0.5) / 44.0,
            (roughnessCoordinate * 31.0 + 0.5) / 32.0,
            (zOffset + logicalZ * zExtent + 0.5) / 159.0);
    float4 encoded = transmissionGgxEnergy.sampleLevel(uvw);

    float a = 0.5 * (sqrt(1.0 + primeOpenPbrMicrofacetAlpha2(microfacet)
            * (1.0 - mu * mu) / (mu * mu)) + 1.0);
    float inverseA = 1.0 / a;
    float etaOneLog2T = 1.0 - 2.0 * a + 0.5 * log2(PRIME_OPENPBR_PI * a) - 1.45676773e-5
            + inverseA * (1.80676418e-1 + inverseA
            * (-1.85532904e-3 - 4.61868116e-3 * inverseA));
    float etaQ0 = 3.0e-4 + 6.82190321e-2 * roughness * mu;
    float etaFactor = etaQ0 / (q + etaQ0) * etaOneLog2T;
    float qFraction = frac(logicalZ * zExtent);
    float transmissionCorrection = (2.0 / 3.0) * encoded.w
            * qFraction * (1.0 - qFraction);
    float2 scale = exp2(clamp(
            float2(encoded.x, encoded.y - etaFactor + transmissionCorrection),
            float2(-80.0),
            float2(80.0)));
    float encodedB = clamp(encoded.z, 0.0, 1.0);
    float bEndpoint = 1.0 - abs(2.0 * encodedB - 1.0);
    float bEdge = 0.5 * primeOpenPbrSquare(bEndpoint) * sqrt(bEndpoint);
    float helperR = encodedB <= 0.5 ? bEdge : 1.0 - bEdge;
    return float3(scale, helperR);
}

public float primeOpenPbrMicrofacetDirectionalAlbedoUnity(
        PrimeOpenPbrMicrofacet microfacet, float cosineTheta) {
    return primeOpenPbrMicrofacetDirectionalAlbedoReflection(
            microfacet, cosineTheta, float3(1.0), float3(1.0)).x;
}

public struct PrimeOpenPbrReflectiveEvalBase {
    public float factor;
    public float microCosine;
    public uint flags;
};

public struct PrimeOpenPbrReflectiveSampleBase {
    public PrimeOpenPbrSample bsdfSample;
    public float microCosine;
};

public PrimeOpenPbrReflectiveEvalBase primeOpenPbrReflectiveEvalBase(
        float3 wi,
        float3 wo,
        PrimeOpenPbrMicrofacet microfacet,
        uint samplingFlags,
        uint checkFlags) {
    PrimeOpenPbrReflectiveEvalBase result;
    result.factor = 0.0;
    result.microCosine = 0.0;
    result.flags = PRIME_OPENPBR_FLAG_NONE;
    if (primeOpenPbrMicrofacetEffectivelySmooth(microfacet)
            || wi.z < 0.0 || wo.z <= 0.0
            || (samplingFlags & (checkFlags & PRIME_OPENPBR_FLAG_SPECULAR)) == 0u) {
        return result;
    }
    float3 halfVector = wi + wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (lengthSquared == 0.0) {
        return result;
    }
    halfVector *= rsqrt(lengthSquared);
    result.microCosine = dot(wi, halfVector);
    result.factor = primeOpenPbrMicrofacetD(microfacet, halfVector)
            * primeOpenPbrMicrofacetG2r(microfacet, wi, wo) / (4.0 * abs(wi.z));
    result.flags = PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION;
    return result;
}

public PrimeOpenPbrReflectiveSampleBase primeOpenPbrReflectiveSampleBase(
        float3 wi,
        float3 randomValue,
        PrimeOpenPbrMicrofacet microfacet,
        uint samplingFlags,
        uint checkFlags) {
    PrimeOpenPbrReflectiveSampleBase result;
    result.bsdfSample = primeOpenPbrZeroSample();
    result.microCosine = 0.0;
    if (wi.z < 0.0) {
        return result;
    }
    float3 halfVector = float3(0.0, 0.0, 1.0);
    float microfacetPdf = 1.0;
    float distribution = 1.0;
    if (!primeOpenPbrMicrofacetEffectivelySmooth(microfacet)) {
        if ((samplingFlags & (checkFlags & PRIME_OPENPBR_FLAG_SPECULAR)) == 0u) {
            return result;
        }
        halfVector = primeOpenPbrMicrofacetSample(microfacet, wi, randomValue.xy, true);
        microfacetPdf = primeOpenPbrMicrofacetPdf(microfacet, wi, halfVector, true);
        distribution = primeOpenPbrMicrofacetD(microfacet, halfVector);
    } else if ((samplingFlags & (checkFlags & PRIME_OPENPBR_FLAG_DELTA)) == 0u) {
        return result;
    }
    float3 wo = reflect(-wi, halfVector);
    if (wo.z <= 0.0) {
        return result;
    }
    result.microCosine = dot(halfVector, wi);
    result.bsdfSample.wo = wo;
    if (primeOpenPbrMicrofacetEffectivelySmooth(microfacet)) {
        result.bsdfSample.throughput.value = float3(1.0);
        result.bsdfSample.throughput.flags = PRIME_OPENPBR_FLAG_DELTA_REFLECTION;
        result.bsdfSample.pdf = 1.0;
    } else {
        result.bsdfSample.throughput.value = float3(distribution
                * primeOpenPbrMicrofacetG2r(microfacet, wi, wo) / abs(4.0 * wi.z));
        result.bsdfSample.throughput.flags = PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION;
        result.bsdfSample.pdf = microfacetPdf / (4.0 * abs(result.microCosine));
    }
    return result;
}

public float primeOpenPbrReflectivePdf(
        float3 wi,
        float3 wo,
        PrimeOpenPbrMicrofacet microfacet,
        uint samplingFlags,
        uint checkFlags) {
    if (primeOpenPbrMicrofacetEffectivelySmooth(microfacet)
            || wi.z < 0.0 || wo.z <= 0.0
            || (samplingFlags & (checkFlags & PRIME_OPENPBR_FLAG_SPECULAR)) == 0u) {
        return 0.0;
    }
    float3 halfVector = wi + wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (lengthSquared == 0.0) {
        return 0.0;
    }
    halfVector *= rsqrt(lengthSquared);
    return primeOpenPbrMicrofacetPdf(microfacet, wi, halfVector, true)
            / (4.0 * abs(dot(wi, halfVector)));
}
