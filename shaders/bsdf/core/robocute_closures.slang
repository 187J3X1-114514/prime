#language slang 2026
module "bsdf/core/robocute_closures.slang";

import "bsdf/core/robocute_common.slang";
import "bsdf/core/robocute_fresnel.slang";
import "bsdf/core/robocute_microfacet.slang";
import "bsdf/core/transmission_ggx_energy.slang";

// Mechanical Slang port of the production-reachable declaration closure from
// robocute_bsdf_closures.glsl at RoboCute commit 0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8.

static const float PRIME_RC_EON_CONSTANT1 = 0.5 - 2.0 / (3.0 * PRIME_RC_PI);

static const float PRIME_RC_EON_CONSTANT2 = 2.0 / 3.0 - 28.0 / (15.0 * PRIME_RC_PI);

float primeRcEonEnergyApprox(float mu, float roughness) {
    float complement = 1.0 - mu;
    float gOverPi = complement * (0.0571085289 + complement
            * (0.491881867 + complement * (-0.332181442 + complement * 0.0714429953)));
    return (1.0 + roughness * gOverPi) / (1.0 + PRIME_RC_EON_CONSTANT1 * roughness);
}

float primeRcEonEnergyApprox2(float mu, float roughness) {
    float complement = 1.0 - mu;
    float gOverPi = complement * (0.0571085289 + complement
            * (0.491881867 + complement * (-0.332181442 + complement * 0.0714429953)));
    return 1.0 + roughness * gOverPi;
}

float3x3 primeRcEonBasis(float3 wi) {
    float lengthSquared = dot(wi.xy, wi.xy);
    float3 x = lengthSquared > 0.0
            ? float3(wi.x, wi.y, 0.0) * rsqrt(lengthSquared)
            : float3(1.0, 0.0, 0.0);
    float3 y = float3(-x.y, x.x, 0.0);
    return float3x3(x, y, float3(0.0, 0.0, 1.0));
}

float4 primeRcEonLtcCoefficients(float mu, float roughness) {
    float a = 1.0 + roughness * (0.303392
            + (-0.518982 + 0.111709 * mu) * mu
            + (-0.276266 + 0.335918 * mu) * roughness);
    float b = roughness * (-1.16407 + 1.15859 * mu
            + (0.150815 - 0.150105 * mu) * roughness)
            / (mu * mu * mu - 1.43545);
    float c = 1.0 + roughness * (0.20013 + (-0.506373 + 0.261777 * mu) * mu);
    float d = roughness * (0.540852 + (-1.01625 + 0.475392 * mu) * mu)
            / (-1.0743 + (0.0725628 + mu) * mu);
    return float4(a, b, c, d);
}

float4 primeRcEonCltcSample(float3 wi, float roughness, float2 sampleValue) {
    float4 coefficients = primeRcEonLtcCoefficients(wi.z, roughness);
    float radius = sqrt(sampleValue.x);
    float phi = 2.0 * PRIME_RC_PI * sampleValue.y;
    float x = radius * cos(phi);
    float y = radius * sin(phi);
    float vz = 1.0 / sqrt(coefficients.w * coefficients.w + 1.0);
    float s = 0.5 * (1.0 + vz);
    x = -lerp(sqrt(1.0 - y * y), x, s);
    float3 wh = float3(x, y, sqrt(max(1.0 - (x * x + y * y), 0.0)));
    float pdfWh = wh.z / (PRIME_RC_PI * s);
    float3 wo = float3(
            coefficients.x * wh.x + coefficients.y * wh.z,
            coefficients.z * wh.y,
            coefficients.w * wh.x + wh.z);
    float woLength = length(wo);
    float determinant = coefficients.z
            * (coefficients.x - coefficients.y * coefficients.w);
    float pdf = pdfWh * woLength * woLength * woLength / determinant;
    wo = normalize(mul(wo, primeRcEonBasis(wi)));
    return float4(wo, pdf);
}

float primeRcEonCltcPdf(float3 wi, float3 woLocal, float roughness) {
    float3 wo = mul(primeRcEonBasis(wi), woLocal);
    float4 coefficients = primeRcEonLtcCoefficients(wi.z, roughness);
    float determinant = coefficients.z
            * (coefficients.x - coefficients.y * coefficients.w);
    float3 wh = float3(
            coefficients.z * (wo.x - coefficients.y * wo.z),
            (coefficients.x - coefficients.y * coefficients.w) * wo.y,
            -coefficients.z * (coefficients.w * wo.x - coefficients.x * wo.z));
    float lengthSquared = dot(wh, wh);
    float vz = 1.0 / sqrt(coefficients.w * coefficients.w + 1.0);
    float s = 0.5 * (1.0 + vz);
    return determinant * determinant / (lengthSquared * lengthSquared)
            * max(wh.z, 0.0) / (PRIME_RC_PI * s);
}

float primeRcEonUniformPdf(float mu, float roughness) {
    return pow(roughness, 0.1)
            * (0.162925 + (-0.372058 + (0.538233 - 0.290822 * mu) * mu) * mu);
}

public PrimeRcThroughput primeRcDiffuseEval(float3 wi, float3 wo, PrimeRcState state) {
    float muI = wi.z;
    float muO = wo.z;
    float baseWeight = state.material.weight.base;
    float roughness = state.material.weight.diffuseRoughness;
    if (muI < 0.0 || muO < 0.0 || baseWeight <= 0.0
            || (state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return primeRcZeroThroughput();
    }
    float s = dot(wi, wo) - muI * muO;
    float sOverF = s > 0.0 ? s / max(muI, muO) : s;
    float af = 1.0 / (1.0 + PRIME_RC_EON_CONSTANT1 * roughness);
    float3 color = state.material.base.color;
    float3 single = color * af * (1.0 + roughness * sOverF);
    float outgoingEnergy = primeRcEonEnergyApprox2(muO, roughness) * af;
    float incidentEnergy = primeRcEonEnergyApprox2(muI, roughness) * af;
    float averageEnergy = af * (1.0 + PRIME_RC_EON_CONSTANT2 * roughness);
    float3 multipleColor = color * color * averageEnergy
            / (float3(1.0) - color * (1.0 - averageEnergy));
    float3 multiple = multipleColor * (1.0 - outgoingEnergy) * (1.0 - incidentEnergy)
            / max(1.0e-7, 1.0 - averageEnergy);
    PrimeRcThroughput result;
    result.value = (single + multiple) * PRIME_RC_INV_PI * muO * baseWeight;
    result.flags = PRIME_RC_FLAG_DIFFUSE_REFLECTION;
    return result;
}

public PrimeRcSampleResult primeRcDiffuseSample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result = primeRcZeroSampleResult(state, stack);
    float baseWeight = state.material.weight.base;
    float roughness = state.material.weight.diffuseRoughness;
    if (wi.z < 0.0 || baseWeight <= 0.0
            || (state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return result;
    }
    float uniformProbability = primeRcEonUniformPdf(wi.z, roughness);
    float cltcPdf;
    if (randomValue.z <= uniformProbability) {
        result.bsdfSample.wo = primeRcUniformSampleHemisphere(randomValue.xy);
        cltcPdf = primeRcEonCltcPdf(wi, result.bsdfSample.wo, roughness);
    } else {
        float4 sampled = primeRcEonCltcSample(wi, roughness, randomValue.xy);
        result.bsdfSample.wo = sampled.xyz;
        cltcPdf = sampled.w;
    }
    const float uniformPdf = 1.0 / (2.0 * PRIME_RC_PI);
    result.bsdfSample.pdf = lerp(cltcPdf, uniformPdf, uniformProbability);
    result.bsdfSample.throughput = primeRcDiffuseEval(wi, result.bsdfSample.wo, state);
    return result;
}

public float primeRcDiffusePdf(float3 wi, float3 wo, PrimeRcState state) {
    float baseWeight = state.material.weight.base;
    float roughness = state.material.weight.diffuseRoughness;
    if (wi.z < 0.0 || wo.z <= 0.0 || baseWeight <= 0.0
            || (state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return 0.0;
    }
    return lerp(
            primeRcEonCltcPdf(wi, wo, roughness),
            1.0 / (2.0 * PRIME_RC_PI),
            primeRcEonUniformPdf(wi.z, roughness));
}

public float3 primeRcDiffuseEnergy(float3 wi, PrimeRcState state) {
    float baseWeight = state.material.weight.base;
    float roughness = state.material.weight.diffuseRoughness;
    if ((state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u
            || baseWeight <= 0.0) {
        return float3(0.0);
    }
    float directionalEnergy = primeRcEonEnergyApprox(wi.z, roughness);
    float af = 1.0 / (1.0 + PRIME_RC_EON_CONSTANT1 * roughness);
    float averageEnergy = af * (1.0 + PRIME_RC_EON_CONSTANT2 * roughness);
    float3 color = state.material.base.color;
    float3 multipleColor = color * color * averageEnergy
            / (float3(1.0) - color * (1.0 - averageEnergy));
    return lerp(multipleColor, color, directionalEnergy) * baseWeight;
}

public PrimeRcThroughput primeRcLambertEval(float3 wi, float3 wo, PrimeRcState state) {
    float baseWeight = state.material.weight.base;
    if (wi.z < 0.0 || wo.z < 0.0 || baseWeight <= 0.0
            || (state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return primeRcZeroThroughput();
    }
    PrimeRcThroughput result;
    result.value = state.material.base.color * (baseWeight * PRIME_RC_INV_PI * wo.z);
    result.flags = PRIME_RC_FLAG_DIFFUSE_REFLECTION;
    return result;
}

public PrimeRcSampleResult primeRcLambertSample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result = primeRcZeroSampleResult(state, stack);
    float baseWeight = state.material.weight.base;
    if (wi.z < 0.0 || baseWeight <= 0.0
            || (state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return result;
    }
    result.bsdfSample.wo = primeRcCosineSampleHemisphere(randomValue.xy);
    result.bsdfSample.pdf = PRIME_RC_INV_PI * result.bsdfSample.wo.z;
    result.bsdfSample.throughput.value = state.material.base.color
            * (baseWeight * PRIME_RC_INV_PI * result.bsdfSample.wo.z);
    result.bsdfSample.throughput.flags = PRIME_RC_FLAG_DIFFUSE_REFLECTION;
    return result;
}

public float primeRcLambertPdf(float3 wi, float3 wo, PrimeRcState state) {
    return wi.z >= 0.0 && wo.z > 0.0 && state.material.weight.base > 0.0
            ? PRIME_RC_INV_PI * wo.z : 0.0;
}

public float3 primeRcLambertEnergy(float3 wi, PrimeRcState state) {
    if (wi.z < 0.0 || state.material.weight.base <= 0.0
            || (state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return float3(0.0);
    }
    return state.material.base.color * state.material.weight.base;
}

public PrimeRcSampleResult primeRcSpecularSample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result = primeRcZeroSampleResult(state, stack);
    PrimeRcReflectiveSampleBase base = primeRcReflectiveSampleBase(
            wi, randomValue, state.specularMicrofacet,
            state.samplingFlags, PRIME_RC_FLAG_REFLECTION);
    result.bsdfSample = base.bsdfSample;
    result.bsdfSample.throughput.value *= primeRcSpecularFresnelUnpolarized(
            state.specularFresnel,
            state.wavelengthsNm,
            state.spectrumed,
            base.microCosine);
    if (primeRcIsNonDelta(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *= state.specularMultipleScattering.x;
    }
    return result;
}

public float primeRcSpecularPdf(float3 wi, float3 wo, PrimeRcState state) {
    return primeRcReflectivePdf(
            wi, wo, state.specularMicrofacet,
            state.samplingFlags, PRIME_RC_FLAG_REFLECTION);
}

public float3 primeRcSpecularTintOut(float3 wo, PrimeRcState state) { return float3(1.0); }

public float3 primeRcSpecularTrans(float3 wi, PrimeRcState state, float3 baseEnergy) {
    if (wi.z < 0.0) { return float3(1.0); }
    return float3(1.0 - state.specularMultipleScattering.z);
}

public float3 primeRcSpecularEnergy(float3 wi, PrimeRcState state) {
    if (wi.z < 0.0) { return float3(0.0); }
    if (!primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)
            && state.specularFresnel.ior != 1.0) {
        if (!primeRcIsSpecular(state.samplingFlags)) { return float3(0.0); }
    } else if (!primeRcIsDelta(state.samplingFlags)) {
        return float3(0.0);
    }
    if (!primeRcIsReflective(state.samplingFlags)) { return float3(0.0); }
    return state.specularMultipleScattering.z * state.specularFresnel.color;
}

public PrimeRcSampleResult primeRcConductorSample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result = primeRcZeroSampleResult(state, stack);
    PrimeRcReflectiveSampleBase base = primeRcReflectiveSampleBase(
            wi, randomValue, state.specularMicrofacet,
            state.samplingFlags, PRIME_RC_FLAG_REFLECTION);
    result.bsdfSample = base.bsdfSample;
    result.bsdfSample.throughput.value *= primeRcConductorFresnelUnpolarized(
            state.conductorFresnel,
            state.wavelengthsNm,
            state.spectrumed,
            base.microCosine);
    if (primeRcIsNonDelta(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *= float3(1.0) + state.conductorFresnel.f0
                * (1.0 - state.conductorEss) / state.conductorEss;
    }
    return result;
}

public float primeRcConductorPdf(float3 wi, float3 wo, PrimeRcState state) {
    return primeRcReflectivePdf(
            wi, wo, state.specularMicrofacet,
            state.samplingFlags, PRIME_RC_FLAG_REFLECTION);
}

public float3 primeRcConductorEnergy(float3 wi, PrimeRcState state) {
    if (wi.z < 0.0) { return float3(0.0); }
    if (primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        if ((state.samplingFlags & PRIME_RC_FLAG_DELTA_REFLECTION) == 0u) {
            return float3(0.0);
        }
    } else if ((state.samplingFlags & PRIME_RC_FLAG_SPECULAR_REFLECTION) == 0u) {
        return float3(0.0);
    }
    float3 f0 = state.conductorFresnel.energyF0;
    return primeRcMicrofacetDirectionalAlbedoReflection(
            state.specularMicrofacet, wi.z, f0,
            clamp(float3(state.conductorFresnel.weight), float3(0.0), float3(1.0)))
            * (float3(1.0) + f0 * (1.0 - state.conductorEss) / state.conductorEss);
}

public PrimeRcThroughput primeRcSubsurfaceEval(float3 wi, float3 wo, PrimeRcState state) {
    bool reflected = wo.z * wi.z > 0.0;
    if ((reflected && ((state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u
            || state.geometryThinWalled == 0u))
            || (!reflected
            && (state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_TRANSMISSION) == 0u)) {
        return primeRcZeroThroughput();
    }
    PrimeRcThroughput result;
    result.value = float3(PRIME_RC_INV_PI * abs(wo.z));
    if (state.geometryThinWalled != 0u) {
        result.value *= state.material.subsurface.color * 0.5
                * (reflected
                ? 1.0 - state.material.subsurface.scatterAnisotropy
                : 1.0 + state.material.subsurface.scatterAnisotropy);
    }
    result.flags = reflected
            ? PRIME_RC_FLAG_DIFFUSE_REFLECTION
            : PRIME_RC_FLAG_DIFFUSE_TRANSMISSION;
    return result;
}

public PrimeRcSampleResult primeRcSubsurfaceSample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result = primeRcZeroSampleResult(state, stack);
    if ((state.samplingFlags & PRIME_RC_FLAG_DIFFUSE) == 0u) { return result; }
    float reflectionProbability = state.geometryThinWalled != 0u
            ? (1.0 - state.material.subsurface.scatterAnisotropy) * 0.5 : 0.0;
    float transmissionProbability = state.geometryThinWalled != 0u
            ? (1.0 + state.material.subsurface.scatterAnisotropy) * 0.5 : 1.0;
    if (!primeRcIsReflective(state.samplingFlags)) { reflectionProbability = 0.0; }
    if (!primeRcIsTransmissive(state.samplingFlags)) { transmissionProbability = 0.0; }
    if (reflectionProbability == 0.0 && transmissionProbability == 0.0) { return result; }
    result.bsdfSample.wo = primeRcCosineSampleHemisphere(randomValue.xy);
    result.bsdfSample.pdf = PRIME_RC_INV_PI * result.bsdfSample.wo.z;
    result.bsdfSample.throughput.value = float3(PRIME_RC_INV_PI * result.bsdfSample.wo.z);
    if (wi.z < 0.0) { result.bsdfSample.wo.z = -result.bsdfSample.wo.z; }
    if (randomValue.z <= transmissionProbability) {
        result.bsdfSample.wo.z = -result.bsdfSample.wo.z;
        result.bsdfSample.pdf *= transmissionProbability;
        result.bsdfSample.throughput.value *= transmissionProbability;
        result.bsdfSample.throughput.flags = PRIME_RC_FLAG_DIFFUSE_TRANSMISSION;
        if (state.geometryThinWalled != 0u) {
            result.bsdfSample.throughput.value *= state.material.subsurface.color;
        }
    } else {
        result.bsdfSample.pdf *= reflectionProbability;
        result.bsdfSample.throughput.value *= reflectionProbability
                * state.material.subsurface.color;
        result.bsdfSample.throughput.flags = PRIME_RC_FLAG_DIFFUSE_REFLECTION;
    }
    return result;
}

public float primeRcSubsurfacePdf(float3 wi, float3 wo, PrimeRcState state) {
    float reflectionProbability = state.geometryThinWalled != 0u
            ? (1.0 - state.material.subsurface.scatterAnisotropy) * 0.5 : 0.0;
    float transmissionProbability = state.geometryThinWalled != 0u
            ? (1.0 + state.material.subsurface.scatterAnisotropy) * 0.5 : 1.0;
    if (!primeRcIsReflective(state.samplingFlags)) { reflectionProbability = 0.0; }
    if (!primeRcIsTransmissive(state.samplingFlags)) { transmissionProbability = 0.0; }
    if (reflectionProbability == 0.0 && transmissionProbability == 0.0) { return 0.0; }
    return PRIME_RC_INV_PI * abs(wo.z)
            * (wi.z * wo.z > 0.0 ? reflectionProbability : transmissionProbability);
}

public float3 primeRcSubsurfaceEnergy(float3 wi, PrimeRcState state) {
    if ((state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) != 0u) {
        return state.geometryThinWalled == 0u
                ? float3(1.0) : state.material.subsurface.color;
    }
    return float3(0.0);
}

float3 primeRcGuardedThinWallSeriesReciprocal(float3 roundTrip) {
    return float3(1.0) / max(
            float3(PRIME_RC_DENOM_TOLERANCE),
            float3(1.0) - primeRcSquare(roundTrip));
}

PrimeRcVecPair primeRcTransmissionThinWallRt(float cosineTheta, PrimeRcState state) {
    PrimeRcVecPair fresnel = primeRcSpecularFresnelRt(
            state.specularFresnel,
            state.wavelengthsNm,
            state.spectrumed,
            cosineTheta);
    float t0 = 1.0 - (1.0 - cosineTheta * cosineTheta)
            / primeRcSquare(state.specularFresnel.ior);
    if (t0 <= 0.0) { return fresnel; }
    float cosineThetaI = sqrt(t0);
    float3 absorption = state.transmissionTint
            * exp(-state.transmissionVolume.extinction / cosineThetaI);
    float3 inverseSeries = primeRcGuardedThinWallSeriesReciprocal(
            fresnel.first * absorption);
    PrimeRcVecPair result;
    result.first = fresnel.first
            * (float3(1.0) + primeRcSquare(fresnel.second * absorption) * inverseSeries);
    result.second = primeRcSquare(fresnel.second) * absorption * inverseSeries;
    return result;
}

public float3 primeRcTransmissionMaterialFrame(float3 w, PrimeRcState state) {
    return state.entering != 0u ? w : float3(-w.x, w.y, -w.z);
}

public PrimeRcThroughput primeRcTransmissionEval(float3 wi, float3 inputWo, PrimeRcState state) {
    if (state.geometryThinWalled != 0u) {
        float3 wo = inputWo;
        bool transmitted = false;
        if (wo.z < 0.0) {
            wo.z = -wo.z;
            transmitted = true;
            if (!primeRcIsTransmissive(state.samplingFlags)) {
                return primeRcZeroThroughput();
            }
        } else if (!primeRcIsReflective(state.samplingFlags)) {
            return primeRcZeroThroughput();
        }
        PrimeRcReflectiveEvalBase base = primeRcReflectiveEvalBase(
                wi, wo, state.transmissionMicrofacet,
                state.samplingFlags, PRIME_RC_FLAG_ALL);
        PrimeRcThroughput result;
        result.value = float3(base.factor);
        result.flags = base.flags;
        if (result.flags != PRIME_RC_FLAG_NONE) {
            PrimeRcVecPair rt = primeRcTransmissionThinWallRt(base.microCosine, state);
            if (transmitted) {
                result.value *= rt.second;
                result.flags = primeRcIsDelta(result.flags)
                        ? PRIME_RC_FLAG_DELTA_TRANSMISSION
                        : PRIME_RC_FLAG_SPECULAR_TRANSMISSION;
            } else {
                result.value *= rt.first;
            }
            if (primeRcIsNonDelta(result.flags)) {
                result.value /= primeRcMicrofacetDirectionalAlbedoUnity(
                        state.transmissionMicrofacet, wi.z);
            }
        }
        return result;
    }
    float3 materialWi = primeRcTransmissionMaterialFrame(wi, state);
    float3 materialWo = primeRcTransmissionMaterialFrame(inputWo, state);
    PrimeRcThroughput result = primeRcRefractiveEval(materialWi, materialWo, state);
    if (result.flags != PRIME_RC_FLAG_NONE) {
        if (primeRcIsTransmissive(result.flags)) {
            result.value *= state.transmissionTint;
        }
        if (primeRcIsNonDelta(result.flags)) {
            result.value *= primeRcIsTransmissive(result.flags)
                    ? state.transmissionMultipleScattering.y
                    : state.transmissionMultipleScattering.x;
        }
    }
    return result;
}

public PrimeRcSampleResult primeRcTransmissionSample(
        float3 wi, float3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result = primeRcZeroSampleResult(state, stack);
    if (state.geometryThinWalled != 0u) {
        PrimeRcReflectiveSampleBase base = primeRcReflectiveSampleBase(
                wi, randomValue, state.transmissionMicrofacet,
                state.samplingFlags, PRIME_RC_FLAG_ALL);
        result.bsdfSample = base.bsdfSample;
        if (result.bsdfSample.throughput.flags != PRIME_RC_FLAG_NONE) {
            PrimeRcVecPair rt = primeRcTransmissionThinWallRt(base.microCosine, state);
            float reflectionProbability = primeRcSpectrumToWeight(rt.first);
            float transmissionProbability = primeRcSpectrumToWeight(rt.second);
            if (!primeRcIsReflective(state.samplingFlags)) { reflectionProbability = 0.0; }
            if (!primeRcIsTransmissive(state.samplingFlags)) { transmissionProbability = 0.0; }
            float probabilitySum = reflectionProbability + transmissionProbability;
            if (probabilitySum == 0.0) {
                result.bsdfSample = primeRcZeroSample();
                return result;
            }
            if (randomValue.z < transmissionProbability / probabilitySum) {
                result.bsdfSample.throughput.value *= rt.second;
                result.bsdfSample.pdf *= transmissionProbability / probabilitySum;
                result.bsdfSample.throughput.flags = primeRcIsDelta(result.bsdfSample.throughput.flags)
                        ? PRIME_RC_FLAG_DELTA_TRANSMISSION
                        : PRIME_RC_FLAG_SPECULAR_TRANSMISSION;
                result.bsdfSample.wo.z = -result.bsdfSample.wo.z;
            } else {
                result.bsdfSample.throughput.value *= rt.first;
                result.bsdfSample.pdf *= reflectionProbability / probabilitySum;
            }
            if (primeRcIsNonDelta(result.bsdfSample.throughput.flags)) {
                result.bsdfSample.throughput.value /= primeRcMicrofacetDirectionalAlbedoUnity(
                        state.transmissionMicrofacet, wi.z);
            }
        }
        return result;
    }

    float3 materialWi = primeRcTransmissionMaterialFrame(wi, state);
    result.bsdfSample = primeRcRefractiveSample(materialWi, randomValue, state);
    if (result.bsdfSample.throughput.flags == PRIME_RC_FLAG_NONE) { return result; }
    result.bsdfSample.wo = primeRcTransmissionMaterialFrame(
            result.bsdfSample.wo, state);
    if (primeRcIsTransmissive(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *= state.transmissionTint;
    }
    if (primeRcIsNonDelta(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *=
                primeRcIsTransmissive(result.bsdfSample.throughput.flags)
                ? state.transmissionMultipleScattering.y
                : state.transmissionMultipleScattering.x;
    }
    return result;
}

public float primeRcTransmissionPdf(float3 wi, float3 inputWo, PrimeRcState state) {
    if (state.geometryThinWalled != 0u) {
        float3 wo = inputWo;
        bool transmitted = false;
        if (wo.z < 0.0) {
            wo.z = -wo.z;
            transmitted = true;
        }
        float3 halfVector = normalize(wi + wo);
        PrimeRcVecPair rt = primeRcTransmissionThinWallRt(dot(wi, halfVector), state);
        float reflectionProbability = primeRcSpectrumToWeight(rt.first);
        float transmissionProbability = primeRcSpectrumToWeight(rt.second);
        if (!primeRcIsReflective(state.samplingFlags)) { reflectionProbability = 0.0; }
        if (!primeRcIsTransmissive(state.samplingFlags)) { transmissionProbability = 0.0; }
        float probabilitySum = reflectionProbability + transmissionProbability;
        if (probabilitySum == 0.0) { return 0.0; }
        float microfacetPdf = primeRcReflectivePdf(
                wi, wo, state.transmissionMicrofacet,
                state.samplingFlags, PRIME_RC_FLAG_ALL);
        return transmitted
                ? microfacetPdf * transmissionProbability / probabilitySum
                : microfacetPdf * reflectionProbability / probabilitySum;
    }
    return primeRcRefractivePdf(
            primeRcTransmissionMaterialFrame(wi, state),
            primeRcTransmissionMaterialFrame(inputWo, state),
            state);
}

public float3 primeRcTransmissionEnergy<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        float3 wi,
        PrimeRcState state)
    where TransmissionGgxEnergy : IPrimeRcTransmissionGgxEnergy
{
    if (!primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)
            && state.specularFresnel.ior != 1.0) {
        if (!primeRcIsSpecular(state.samplingFlags)) { return float3(0.0); }
    } else if (!primeRcIsDelta(state.samplingFlags)) {
        return float3(0.0);
    }
    float helperR;
    if (state.geometryThinWalled != 0u) {
        helperR = primeRcMicrofacetDielectricMsCompensation(
                transmissionGgxEnergy,
                state.specularMicrofacet, abs(wi.z), state.specularFresnel.ior).z;
    } else {
        helperR = state.transmissionMultipleScattering.z;
    }
    float reflected = primeRcIsReflective(state.samplingFlags) ? helperR : 0.0;
    float transmitted = primeRcIsTransmissive(state.samplingFlags) ? 1.0 - helperR : 0.0;
    return reflected * state.specularFresnel.color
            + transmitted * state.transmissionTint;
}
