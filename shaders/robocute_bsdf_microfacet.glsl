#ifndef PRIME_ROBOCUTE_BSDF_MICROFACET_GLSL
#define PRIME_ROBOCUTE_BSDF_MICROFACET_GLSL

#include "robocute_bsdf_common.glsl"
#include "robocute_bsdf_fresnel.glsl"

// RoboCute's 32^3 RGBA32F transmission table is a required part of the model, not an optional
// optimization. The compile-only validation shader keeps its isolated default set, while the
// runtime BSDF adapter overrides these macros to the generated Prime descriptor contract.
#ifndef PRIME_RC_TRANSMISSION_GGX_SET
#define PRIME_RC_TRANSMISSION_GGX_SET 7
#endif
#ifndef PRIME_RC_TRANSMISSION_GGX_BINDING
#define PRIME_RC_TRANSMISSION_GGX_BINDING 0
#endif
layout(
        set = PRIME_RC_TRANSMISSION_GGX_SET,
        binding = PRIME_RC_TRANSMISSION_GGX_BINDING)
uniform sampler3D primeRcTransmissionGgxEnergy;

bool primeRcMicrofacetEffectivelySmooth(PrimeRcMicrofacet microfacet) {
    return primeRcReduceMax(microfacet.alpha) < 1.0e-4;
}

float primeRcMicrofacetLambda(PrimeRcMicrofacet microfacet, vec3 value) {
    return (sqrt(1.0 + primeRcReduceSum(primeRcSquare(microfacet.alpha * value.xy))
            / primeRcSquare(value.z)) - sign(value.z)) * 0.5;
}

float primeRcMicrofacetD(PrimeRcMicrofacet microfacet, vec3 halfVector) {
    float denominator = primeRcReduceSum(primeRcSquare(halfVector.xy / microfacet.alpha))
            + primeRcSquare(halfVector.z);
    float result = 1.0 / (PRIME_RC_PI * microfacet.alpha.x * microfacet.alpha.y
            * primeRcSquare(denominator));
    return result * halfVector.z > 1.0e-20 ? result : 0.0;
}

float primeRcMicrofacetG1(PrimeRcMicrofacet microfacet, vec3 value) {
    return 1.0 / primeRcMicrofacetLambda(microfacet, -value);
}

float primeRcMicrofacetG2r(PrimeRcMicrofacet microfacet, vec3 wi, vec3 wo) {
    if (wi.z < 0.0) {
        wi = -wi;
        wo = -wo;
    }
    return 1.0 / (primeRcMicrofacetLambda(microfacet, -wi)
            + primeRcMicrofacetLambda(microfacet, wo));
}

float primeRcLogGamma(float value) {
    const float gam0 = 1.0 / 12.0;
    const float gam1 = 1.0 / 30.0;
    const float gam2 = 53.0 / 210.0;
    const float gam3 = 195.0 / 371.0;
    const float gam4 = 22999.0 / 22737.0;
    const float gam5 = 29944523.0 / 19733142.0;
    const float gam6 = 109535241009.0 / 48264275462.0;
    return 0.5 * log(2.0 * PRIME_RC_PI) - value + (value - 0.5) * log(value)
            + gam0 / (value + gam1 / (value + gam2 / (value + gam3
            / (value + gam4 / (value + gam5 / (value + gam6 / value))))));
}

float primeRcGamma(float value) {
    return exp(primeRcLogGamma(value + 5.0))
            / (value * (value + 1.0) * (value + 2.0) * (value + 3.0) * (value + 4.0));
}

float primeRcBeta(float first, float second) {
    return primeRcGamma(first) * primeRcGamma(second) / primeRcGamma(first + second);
}

float primeRcMicrofacetG2t(PrimeRcMicrofacet microfacet, vec3 wi, vec3 wo) {
    if (wi.z < 0.0) {
        wi = -wi;
        wo = -wo;
    }
    return primeRcBeta(
            primeRcMicrofacetLambda(microfacet, -wi),
            primeRcMicrofacetLambda(microfacet, wo));
}

vec3 primeRcMicrofacetSample(
        PrimeRcMicrofacet microfacet,
        vec3 wi,
        vec2 sampleValue,
        bool reflectOnly) {
    vec3 visibleHalf = normalize(vec3(microfacet.alpha * wi.xy, wi.z));
    float phi = 2.0 * PRIME_RC_PI * sampleValue.x;
    float k = 1.0;
    if (reflectOnly) {
        float a = clamp(min(microfacet.alpha.x, microfacet.alpha.y), 0.0, 1.0);
        float s = 1.0 + length(wi.xy);
        float a2 = a * a;
        float s2 = s * s;
        k = (s2 - a2 * s2) / (s2 + a2 * wi.z * wi.z);
    }
    float z = mix(1.0, -k * visibleHalf.z, sampleValue.y);
    float sine = sqrt(clamp(1.0 - z * z, 0.0, 1.0));
    vec3 halfVector = vec3(sine * cos(phi), sine * sin(phi), z) + visibleHalf;
    return normalize(vec3(microfacet.alpha * halfVector.xy, max(0.0, halfVector.z)));
}

float primeRcMicrofacetPdf(
        PrimeRcMicrofacet microfacet,
        vec3 wi,
        vec3 halfVector,
        bool reflectOnly) {
    float lengthV = length(vec3(microfacet.alpha * wi.xy, wi.z));
    float k = 1.0;
    if (reflectOnly) {
        float a = clamp(min(microfacet.alpha.x, microfacet.alpha.y), 0.0, 1.0);
        float s = 1.0 + length(wi.xy);
        float a2 = a * a;
        float s2 = s * s;
        k = (s2 - a2 * s2) / (s2 + a2 * wi.z * wi.z);
    }
    return 2.0 * primeRcMicrofacetD(microfacet, halfVector) * dot(wi, halfVector)
            / (k * wi.z + lengthV);
}

float primeRcMicrofacetAlpha2(PrimeRcMicrofacet microfacet) {
    return 0.5 * primeRcReduceSum(primeRcSquare(microfacet.alpha));
}

vec3 primeRcMicrofacetDirectionalAlbedoReflection(
        PrimeRcMicrofacet microfacet,
        float cosineTheta,
        vec3 f0,
        vec3 f90) {
    float x = cosineTheta;
    float y2 = primeRcMicrofacetAlpha2(microfacet);
    float x2 = x * x;
    float y = sqrt(y2);
    vec4 r = vec4(0.1003, 0.9345, 1.0, 1.0)
            + vec4(-0.6303, -2.323, -1.765, 0.2281) * x
            + vec4(9.748, 2.229, 8.263, 15.94) * y
            + vec4(-2.038, -3.748, 11.53, -55.83) * x * y
            + vec4(29.34, 1.424, 28.96, 13.08) * x2
            + vec4(-8.245, -0.7684, -7.507, 41.26) * y2
            + vec4(-26.44, 1.436, -36.11, 54.9) * x2 * y
            + vec4(19.99, 0.2913, 15.86, 300.2) * x * y2
            + vec4(-5.448, 0.6286, 33.37, -285.1) * x2 * y2;
    vec2 ab = clamp(r.xy / r.zw, vec2(0.0), vec2(1.0));
    return f0 * ab.x + f90 * ab.y;
}

vec2 primeRcMicrofacetDirectionalAlbedoTransmission(
        PrimeRcMicrofacet microfacet,
        float cosineTheta,
        float ior) {
    if (cosineTheta < 0.0) {
        cosineTheta = -cosineTheta;
        ior = 1.0 / ior;
    }
    vec3 size = vec3(32.0);
    vec3 uvw = vec3(
            cosineTheta,
            pow(primeRcMicrofacetAlpha2(microfacet), 0.25),
            abs((1.0 - ior) / (1.0 + ior)));
    uvw = uvw * ((size - 1.0) / size) + 0.5 / size;
    vec4 value = textureLod(primeRcTransmissionGgxEnergy, uvw, 0.0);
    return ior > 1.0 ? value.xy : value.zw;
}

float primeRcMicrofacetDirectionalAlbedoUnity(
        PrimeRcMicrofacet microfacet, float cosineTheta) {
    return primeRcMicrofacetDirectionalAlbedoReflection(
            microfacet, cosineTheta, vec3(1.0), vec3(1.0)).x;
}

struct PrimeRcReflectiveEvalBase {
    float factor;
    float microCosine;
    uint flags;
};

struct PrimeRcReflectiveSampleBase {
    PrimeRcSample bsdfSample;
    float microCosine;
};

PrimeRcReflectiveEvalBase primeRcReflectiveEvalBase(
        vec3 wi,
        vec3 wo,
        PrimeRcMicrofacet microfacet,
        uint samplingFlags,
        uint checkFlags) {
    PrimeRcReflectiveEvalBase result;
    result.factor = 0.0;
    result.microCosine = 0.0;
    result.flags = PRIME_RC_FLAG_NONE;
    if (primeRcMicrofacetEffectivelySmooth(microfacet)
            || wi.z < 0.0 || wo.z <= 0.0
            || (samplingFlags & (checkFlags & PRIME_RC_FLAG_SPECULAR)) == 0u) {
        return result;
    }
    vec3 halfVector = wi + wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (lengthSquared == 0.0) {
        return result;
    }
    halfVector *= inversesqrt(lengthSquared);
    result.microCosine = dot(wi, halfVector);
    result.factor = primeRcMicrofacetD(microfacet, halfVector)
            * primeRcMicrofacetG2r(microfacet, wi, wo) / (4.0 * abs(wi.z));
    result.flags = PRIME_RC_FLAG_SPECULAR_REFLECTION;
    return result;
}

PrimeRcReflectiveSampleBase primeRcReflectiveSampleBase(
        vec3 wi,
        vec3 randomValue,
        PrimeRcMicrofacet microfacet,
        uint samplingFlags,
        uint checkFlags) {
    PrimeRcReflectiveSampleBase result;
    result.bsdfSample = primeRcZeroSample();
    result.microCosine = 0.0;
    if (wi.z < 0.0) {
        return result;
    }
    vec3 halfVector = vec3(0.0, 0.0, 1.0);
    float microfacetPdf = 1.0;
    float distribution = 1.0;
    if (!primeRcMicrofacetEffectivelySmooth(microfacet)) {
        if ((samplingFlags & (checkFlags & PRIME_RC_FLAG_SPECULAR)) == 0u) {
            return result;
        }
        halfVector = primeRcMicrofacetSample(microfacet, wi, randomValue.xy, true);
        microfacetPdf = primeRcMicrofacetPdf(microfacet, wi, halfVector, true);
        distribution = primeRcMicrofacetD(microfacet, halfVector);
    } else if ((samplingFlags & (checkFlags & PRIME_RC_FLAG_DELTA)) == 0u) {
        return result;
    }
    vec3 wo = reflect(-wi, halfVector);
    if (wo.z <= 0.0) {
        return result;
    }
    result.microCosine = dot(halfVector, wi);
    result.bsdfSample.wo = wo;
    if (primeRcMicrofacetEffectivelySmooth(microfacet)) {
        result.bsdfSample.throughput.value = vec3(1.0);
        result.bsdfSample.throughput.flags = PRIME_RC_FLAG_DELTA_REFLECTION;
        result.bsdfSample.pdf = 1.0;
    } else {
        result.bsdfSample.throughput.value = vec3(distribution
                * primeRcMicrofacetG2r(microfacet, wi, wo) / abs(4.0 * wi.z));
        result.bsdfSample.throughput.flags = PRIME_RC_FLAG_SPECULAR_REFLECTION;
        result.bsdfSample.pdf = microfacetPdf / (4.0 * abs(result.microCosine));
    }
    return result;
}

float primeRcReflectivePdf(
        vec3 wi,
        vec3 wo,
        PrimeRcMicrofacet microfacet,
        uint samplingFlags,
        uint checkFlags) {
    if (primeRcMicrofacetEffectivelySmooth(microfacet)
            || wi.z < 0.0 || wo.z <= 0.0
            || (samplingFlags & (checkFlags & PRIME_RC_FLAG_SPECULAR)) == 0u) {
        return 0.0;
    }
    vec3 halfVector = wi + wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (lengthSquared == 0.0) {
        return 0.0;
    }
    halfVector *= inversesqrt(lengthSquared);
    return primeRcMicrofacetPdf(microfacet, wi, halfVector, true)
            / (4.0 * abs(dot(wi, halfVector)));
}

PrimeRcThroughput primeRcRefractiveEval(vec3 wi, vec3 wo, PrimeRcState state) {
    PrimeRcThroughput result = primeRcZeroThroughput();
    PrimeRcMicrofacet microfacet = state.specularMicrofacet;
    PrimeRcSpecularFresnel fresnel = state.specularFresnel;
    if (primeRcMicrofacetEffectivelySmooth(microfacet) || fresnel.ior == 1.0) {
        return result;
    }
    bool reflected = wi.z * wo.z > 0.0;
    float etaPath = reflected ? 1.0 : (wi.z > 0.0 ? fresnel.ior : 1.0 / fresnel.ior);
    vec3 halfVector = wi + etaPath * wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (wo.z == 0.0 || wi.z == 0.0 || lengthSquared == 0.0) {
        return result;
    }
    halfVector *= inversesqrt(lengthSquared);
    if (halfVector.z <= 0.0) { halfVector = -halfVector; }
    float microIncident = dot(wi, halfVector);
    float microOutgoing = dot(wo, halfVector);
    if (microIncident * wi.z < 0.0 || microOutgoing * wo.z < 0.0) {
        return result;
    }
    PrimeRcVecPair rt = primeRcSpecularFresnelRt(
            fresnel, state.wavelengthsNm, state.spectrumed, microIncident);
    float distribution = primeRcMicrofacetD(microfacet, halfVector);
    if (reflected && (state.samplingFlags & PRIME_RC_FLAG_SPECULAR_REFLECTION) != 0u) {
        result.value = rt.first * distribution
                * primeRcMicrofacetG2r(microfacet, wi, wo) / (4.0 * abs(wi.z));
        result.flags = PRIME_RC_FLAG_SPECULAR_REFLECTION;
    } else if (!reflected
            && (state.samplingFlags & PRIME_RC_FLAG_SPECULAR_TRANSMISSION) != 0u) {
        float denominator = primeRcSquare(microOutgoing + microIncident / etaPath) * wi.z;
        result.value = rt.second * distribution
                * primeRcMicrofacetG2t(microfacet, wi, wo)
                * abs(microIncident * microOutgoing / denominator)
                / primeRcSquare(etaPath);
        result.flags = PRIME_RC_FLAG_SPECULAR_TRANSMISSION;
    }
    return result;
}

PrimeRcSample primeRcRefractiveSample(vec3 wi, vec3 randomValue, PrimeRcState state) {
    PrimeRcMicrofacet microfacet = state.specularMicrofacet;
    PrimeRcSpecularFresnel fresnel = state.specularFresnel;
    vec3 halfVector = vec3(0.0, 0.0, 1.0);
    float microfacetPdf = 1.0;
    float distribution = 1.0;
    if (!primeRcMicrofacetEffectivelySmooth(microfacet) && fresnel.ior != 1.0) {
        if (!primeRcIsSpecular(state.samplingFlags)) {
            return primeRcZeroSample();
        }
        vec3 sampleDirection = wi.z > 0.0 ? wi : -wi;
        halfVector = primeRcMicrofacetSample(microfacet, sampleDirection, randomValue.xy, false);
        microfacetPdf = primeRcMicrofacetPdf(
                microfacet, sampleDirection, halfVector, false);
        distribution = primeRcMicrofacetD(microfacet, halfVector);
    } else if (!primeRcIsDelta(state.samplingFlags)) {
        return primeRcZeroSample();
    }
    float microIncident = dot(halfVector, wi);
    PrimeRcVecPair rt = primeRcSpecularFresnelRt(
            fresnel, state.wavelengthsNm, state.spectrumed, microIncident);
    vec3 reflection = rt.first;
    vec3 transmission = rt.second;
    float reflectionProbability = primeRcSpectrumToWeight(reflection);
    float transmissionProbability = primeRcSpectrumToWeight(transmission);
    if (!primeRcIsReflective(state.samplingFlags)) { reflectionProbability = 0.0; }
    if (!primeRcIsTransmissive(state.samplingFlags)) { transmissionProbability = 0.0; }
    PrimeRcRefractResult refracted = primeRcSpecularFresnelRefract(fresnel, -wi, halfVector);
    if (refracted.valid == 0u) {
        transmissionProbability = 0.0;
        reflection = vec3(1.0);
    }
    float probabilitySum = reflectionProbability + transmissionProbability;
    if (probabilitySum == 0.0) {
        return primeRcZeroSample();
    }
    PrimeRcSample result = primeRcZeroSample();
    if (randomValue.z < transmissionProbability / probabilitySum) {
        vec3 wo = refracted.wo;
        if (wi.z * wo.z >= 0.0) { return primeRcZeroSample(); }
        result.wo = wo;
        result.throughput.value = transmission;
        result.throughput.flags = PRIME_RC_FLAG_DELTA_TRANSMISSION;
        result.pdf = transmissionProbability / probabilitySum;
        if (!primeRcMicrofacetEffectivelySmooth(microfacet) && fresnel.ior != 1.0) {
            float microOutgoing = dot(wo, halfVector);
            float eta = microIncident > 0.0 ? fresnel.ior : 1.0 / fresnel.ior;
            float denominator = primeRcSquare(microOutgoing + microIncident / eta);
            result.pdf *= microfacetPdf * abs(microOutgoing) / denominator;
            result.throughput.value = transmission * distribution
                    * primeRcMicrofacetG2t(microfacet, wi, wo)
                    * abs(microIncident * microOutgoing / (wi.z * denominator));
            result.throughput.flags = PRIME_RC_FLAG_SPECULAR_TRANSMISSION;
        }
    } else {
        vec3 wo = reflect(-wi, halfVector);
        if (wi.z * wo.z < 0.0) { return primeRcZeroSample(); }
        result.wo = wo;
        result.throughput.value = reflection;
        result.throughput.flags = PRIME_RC_FLAG_DELTA_REFLECTION;
        result.pdf = reflectionProbability / probabilitySum;
        if (!primeRcMicrofacetEffectivelySmooth(microfacet) && fresnel.ior != 1.0) {
            result.pdf *= microfacetPdf / (4.0 * abs(microIncident));
            result.throughput.value = reflection * distribution
                    * primeRcMicrofacetG2r(microfacet, wi, wo) / abs(4.0 * wi.z);
            result.throughput.flags = PRIME_RC_FLAG_SPECULAR_REFLECTION;
        }
    }
    return result;
}

float primeRcRefractivePdf(vec3 wi, vec3 wo, PrimeRcState state) {
    PrimeRcMicrofacet microfacet = state.specularMicrofacet;
    PrimeRcSpecularFresnel fresnel = state.specularFresnel;
    if (primeRcMicrofacetEffectivelySmooth(microfacet)
            || fresnel.ior == 1.0 || !primeRcIsSpecular(state.samplingFlags)) {
        return 0.0;
    }
    bool reflected = wi.z * wo.z > 0.0;
    float etaPath = reflected ? 1.0 : (wi.z > 0.0 ? fresnel.ior : 1.0 / fresnel.ior);
    vec3 halfVector = wi + etaPath * wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (wo.z == 0.0 || wi.z == 0.0 || lengthSquared == 0.0) { return 0.0; }
    halfVector *= inversesqrt(lengthSquared);
    if (halfVector.z <= 0.0) { halfVector = -halfVector; }
    float microIncident = dot(wi, halfVector);
    float microOutgoing = dot(wo, halfVector);
    if (microIncident * wi.z < 0.0 || microOutgoing * wo.z < 0.0) { return 0.0; }
    PrimeRcVecPair rt = primeRcSpecularFresnelRt(
            fresnel, state.wavelengthsNm, state.spectrumed, microIncident);
    float reflectionProbability = primeRcSpectrumToWeight(rt.first);
    float transmissionProbability = primeRcSpectrumToWeight(rt.second);
    if (!primeRcIsReflective(state.samplingFlags)) { reflectionProbability = 0.0; }
    if (!primeRcIsTransmissive(state.samplingFlags)) { transmissionProbability = 0.0; }
    float probabilitySum = reflectionProbability + transmissionProbability;
    if (probabilitySum == 0.0) { return 0.0; }
    float microfacetPdf = primeRcMicrofacetPdf(
            microfacet, wi.z > 0.0 ? wi : -wi, halfVector, false);
    if (reflected) {
        return microfacetPdf / (4.0 * abs(microIncident))
                * reflectionProbability / probabilitySum;
    }
    float denominator = primeRcSquare(microOutgoing + microIncident / etaPath);
    return microfacetPdf * abs(microOutgoing) / denominator
            * transmissionProbability / probabilitySum;
}

#endif
