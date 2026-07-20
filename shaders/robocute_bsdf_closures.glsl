#ifndef PRIME_ROBOCUTE_BSDF_CLOSURES_GLSL
#define PRIME_ROBOCUTE_BSDF_CLOSURES_GLSL

#include "robocute_bsdf_common.glsl"
#include "robocute_bsdf_fresnel.glsl"
#include "robocute_bsdf_microfacet.glsl"

const float PRIME_RC_EON_CONSTANT1 = 0.5 - 2.0 / (3.0 * PRIME_RC_PI);
const float PRIME_RC_EON_CONSTANT2 = 2.0 / 3.0 - 28.0 / (15.0 * PRIME_RC_PI);

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

mat3 primeRcEonBasis(vec3 wi) {
    float lengthSquared = dot(wi.xy, wi.xy);
    vec3 x = lengthSquared > 0.0
            ? vec3(wi.x, wi.y, 0.0) * inversesqrt(lengthSquared)
            : vec3(1.0, 0.0, 0.0);
    vec3 y = vec3(-x.y, x.x, 0.0);
    return mat3(x, y, vec3(0.0, 0.0, 1.0));
}

vec4 primeRcEonLtcCoefficients(float mu, float roughness) {
    float a = 1.0 + roughness * (0.303392
            + (-0.518982 + 0.111709 * mu) * mu
            + (-0.276266 + 0.335918 * mu) * roughness);
    float b = roughness * (-1.16407 + 1.15859 * mu
            + (0.150815 - 0.150105 * mu) * roughness)
            / (mu * mu * mu - 1.43545);
    float c = 1.0 + roughness * (0.20013 + (-0.506373 + 0.261777 * mu) * mu);
    float d = roughness * (0.540852 + (-1.01625 + 0.475392 * mu) * mu)
            / (-1.0743 + (0.0725628 + mu) * mu);
    return vec4(a, b, c, d);
}

vec4 primeRcEonCltcSample(vec3 wi, float roughness, vec2 sampleValue) {
    vec4 coefficients = primeRcEonLtcCoefficients(wi.z, roughness);
    float radius = sqrt(sampleValue.x);
    float phi = 2.0 * PRIME_RC_PI * sampleValue.y;
    float x = radius * cos(phi);
    float y = radius * sin(phi);
    float vz = 1.0 / sqrt(coefficients.w * coefficients.w + 1.0);
    float s = 0.5 * (1.0 + vz);
    x = -mix(sqrt(1.0 - y * y), x, s);
    vec3 wh = vec3(x, y, sqrt(max(1.0 - (x * x + y * y), 0.0)));
    float pdfWh = wh.z / (PRIME_RC_PI * s);
    vec3 wo = vec3(
            coefficients.x * wh.x + coefficients.y * wh.z,
            coefficients.z * wh.y,
            coefficients.w * wh.x + wh.z);
    float woLength = length(wo);
    float determinant = coefficients.z
            * (coefficients.x - coefficients.y * coefficients.w);
    float pdf = pdfWh * woLength * woLength * woLength / determinant;
    wo = normalize(primeRcEonBasis(wi) * wo);
    return vec4(wo, pdf);
}

float primeRcEonCltcPdf(vec3 wi, vec3 woLocal, float roughness) {
    vec3 wo = transpose(primeRcEonBasis(wi)) * woLocal;
    vec4 coefficients = primeRcEonLtcCoefficients(wi.z, roughness);
    float determinant = coefficients.z
            * (coefficients.x - coefficients.y * coefficients.w);
    vec3 wh = vec3(
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

PrimeRcThroughput primeRcDiffuseEval(vec3 wi, vec3 wo, PrimeRcState state) {
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
    vec3 color = state.material.base.color;
    vec3 single = color * af * (1.0 + roughness * sOverF);
    float outgoingEnergy = primeRcEonEnergyApprox2(muO, roughness) * af;
    float incidentEnergy = primeRcEonEnergyApprox2(muI, roughness) * af;
    float averageEnergy = af * (1.0 + PRIME_RC_EON_CONSTANT2 * roughness);
    vec3 multipleColor = color * color * averageEnergy
            / (vec3(1.0) - color * (1.0 - averageEnergy));
    vec3 multiple = multipleColor * (1.0 - outgoingEnergy) * (1.0 - incidentEnergy)
            / max(1.0e-7, 1.0 - averageEnergy);
    PrimeRcThroughput result;
    result.value = (single + multiple) * PRIME_RC_INV_PI * muO * baseWeight;
    result.flags = PRIME_RC_FLAG_DIFFUSE_REFLECTION;
    return result;
}

PrimeRcSampleResult primeRcDiffuseSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result = primeRcZeroSampleResult(state, stack);
    float baseWeight = state.material.weight.base;
    float roughness = state.material.weight.diffuseRoughness;
    if (wi.z < 0.0 || baseWeight <= 0.0
            || (state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return result;
    }
    float uniformProbability = primeRcEonUniformPdf(wi.z, roughness);
    float cltcPdf;
    if (randomValue.z < uniformProbability) {
        result.bsdfSample.wo = primeRcUniformSampleHemisphere(randomValue.xy);
        cltcPdf = primeRcEonCltcPdf(wi, result.bsdfSample.wo, roughness);
    } else {
        vec4 sampled = primeRcEonCltcSample(wi, roughness, randomValue.xy);
        result.bsdfSample.wo = sampled.xyz;
        cltcPdf = sampled.w;
    }
    const float uniformPdf = 1.0 / (2.0 * PRIME_RC_PI);
    result.bsdfSample.pdf = mix(cltcPdf, uniformPdf, uniformProbability);
    result.bsdfSample.throughput = primeRcDiffuseEval(wi, result.bsdfSample.wo, state);
    return result;
}

float primeRcDiffusePdf(vec3 wi, vec3 wo, PrimeRcState state) {
    float baseWeight = state.material.weight.base;
    float roughness = state.material.weight.diffuseRoughness;
    if (wi.z < 0.0 || wo.z <= 0.0 || baseWeight <= 0.0
            || (state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return 0.0;
    }
    return mix(
            primeRcEonCltcPdf(wi, wo, roughness),
            1.0 / (2.0 * PRIME_RC_PI),
            primeRcEonUniformPdf(wi.z, roughness));
}

vec3 primeRcDiffuseTintOut(vec3 wo, PrimeRcState state) { return vec3(0.0); }
vec3 primeRcDiffuseTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    return vec3(0.0);
}

vec3 primeRcDiffuseEnergy(vec3 wi, PrimeRcState state) {
    float baseWeight = state.material.weight.base;
    float roughness = state.material.weight.diffuseRoughness;
    if ((state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u
            || baseWeight <= 0.0) {
        return vec3(0.0);
    }
    float directionalEnergy = primeRcEonEnergyApprox(wi.z, roughness);
    float af = 1.0 / (1.0 + PRIME_RC_EON_CONSTANT1 * roughness);
    float averageEnergy = af * (1.0 + PRIME_RC_EON_CONSTANT2 * roughness);
    vec3 color = state.material.base.color;
    vec3 multipleColor = color * color * averageEnergy
            / (vec3(1.0) - color * (1.0 - averageEnergy));
    return mix(multipleColor, color, directionalEnergy) * baseWeight;
}

PrimeRcThroughput primeRcLambertEval(vec3 wi, vec3 wo, PrimeRcState state) {
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

PrimeRcSampleResult primeRcLambertSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
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

float primeRcLambertPdf(vec3 wi, vec3 wo, PrimeRcState state) {
    return wi.z >= 0.0 && wo.z > 0.0 && state.material.weight.base > 0.0
            ? PRIME_RC_INV_PI * wo.z : 0.0;
}

vec3 primeRcLambertTintOut(vec3 wo, PrimeRcState state) { return vec3(0.0); }
vec3 primeRcLambertTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    return vec3(0.0);
}

vec3 primeRcLambertEnergy(vec3 wi, PrimeRcState state) {
    if (wi.z < 0.0 || state.material.weight.base <= 0.0
            || (state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return vec3(0.0);
    }
    return state.material.base.color * state.material.weight.base;
}

PrimeRcThroughput primeRcSpecularEval(vec3 wi, vec3 wo, PrimeRcState state) {
    PrimeRcReflectiveEvalBase base = primeRcReflectiveEvalBase(
            wi, wo, state.specularMicrofacet, state.samplingFlags, PRIME_RC_FLAG_REFLECTION);
    PrimeRcThroughput result;
    result.value = primeRcSpecularFresnelUnpolarized(
            state.specularFresnel,
            state.wavelengthsNm,
            state.spectrumed,
            base.microCosine) * base.factor;
    result.flags = base.flags;
    if (primeRcIsNonDelta(result.flags)) {
        result.value /= primeRcReduceSum(primeRcMicrofacetDirectionalAlbedoTransmission(
                state.specularMicrofacet, wi.z, state.specularFresnel.energyIor));
    }
    return result;
}

PrimeRcSampleResult primeRcSpecularSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
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
        result.bsdfSample.throughput.value /= primeRcReduceSum(
                primeRcMicrofacetDirectionalAlbedoTransmission(
                        state.specularMicrofacet, wi.z,
                        state.specularFresnel.energyIor));
    }
    return result;
}

float primeRcSpecularPdf(vec3 wi, vec3 wo, PrimeRcState state) {
    return primeRcReflectivePdf(
            wi, wo, state.specularMicrofacet,
            state.samplingFlags, PRIME_RC_FLAG_REFLECTION);
}

vec3 primeRcSpecularTintOut(vec3 wo, PrimeRcState state) { return vec3(1.0); }

vec3 primeRcSmoothSpecularReflectance(vec3 wi, PrimeRcState state, bool includeTint) {
    // The GGX directional-energy table resolves finite microfacet distributions. Its first
    // grazing-angle texel is necessarily an average over a finite interval, whereas a delta
    // interface has an exact analytic Fresnel value. Using that texel to choose between the
    // delta coat and its substrate makes the sampling probability disagree with the sampled
    // throughput (most visibly at grazing angles), which breaks the layer's white-furnace
    // contract. Keep the LUT for every finite lobe and use the closure's own Fresnel for the
    // measure-zero delta limit.
    PrimeRcSpecularFresnel fresnel = state.specularFresnel;
    if (!includeTint) {
        fresnel.color = vec3(1.0);
    }
    return primeRcSpecularFresnelUnpolarized(
            fresnel,
            state.wavelengthsNm,
            state.spectrumed,
            clamp(wi.z, 0.0, 1.0));
}

vec3 primeRcSpecularTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    if (wi.z < 0.0) { return vec3(1.0); }
    if (primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        return vec3(1.0) - primeRcSmoothSpecularReflectance(wi, state, false);
    }
    vec2 energy = primeRcMicrofacetDirectionalAlbedoTransmission(
            state.specularMicrofacet, wi.z, state.specularFresnel.energyIor);
    return vec3(energy.y / primeRcReduceSum(energy));
}

vec3 primeRcSpecularEnergy(vec3 wi, PrimeRcState state) {
    if (wi.z < 0.0) { return vec3(0.0); }
    if (!primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)
            && state.specularFresnel.ior != 1.0) {
        if (!primeRcIsSpecular(state.samplingFlags)) { return vec3(0.0); }
    } else if (!primeRcIsDelta(state.samplingFlags)) {
        return vec3(0.0);
    }
    if (!primeRcIsReflective(state.samplingFlags)) { return vec3(0.0); }
    if (primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        return primeRcSmoothSpecularReflectance(wi, state, true);
    }
    vec2 energy = primeRcMicrofacetDirectionalAlbedoTransmission(
            state.specularMicrofacet, wi.z, state.specularFresnel.energyIor);
    return state.specularFresnel.color * (energy.x / primeRcReduceSum(energy));
}

PrimeRcThroughput primeRcConductorEval(vec3 wi, vec3 wo, PrimeRcState state) {
    PrimeRcReflectiveEvalBase base = primeRcReflectiveEvalBase(
            wi, wo, state.specularMicrofacet, state.samplingFlags, PRIME_RC_FLAG_REFLECTION);
    PrimeRcThroughput result;
    result.value = primeRcConductorFresnelUnpolarized(
            state.conductorFresnel,
            state.wavelengthsNm,
            state.spectrumed,
            base.microCosine) * base.factor;
    result.flags = base.flags;
    if (primeRcIsNonDelta(result.flags)) {
        result.value *= vec3(1.0) + state.conductorFresnel.f0
                * (1.0 - state.conductorEss) / state.conductorEss;
    }
    return result;
}

PrimeRcSampleResult primeRcConductorSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
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
        result.bsdfSample.throughput.value *= vec3(1.0) + state.conductorFresnel.f0
                * (1.0 - state.conductorEss) / state.conductorEss;
    }
    return result;
}

float primeRcConductorPdf(vec3 wi, vec3 wo, PrimeRcState state) {
    return primeRcReflectivePdf(
            wi, wo, state.specularMicrofacet,
            state.samplingFlags, PRIME_RC_FLAG_REFLECTION);
}

vec3 primeRcConductorTintOut(vec3 wo, PrimeRcState state) { return vec3(0.0); }
vec3 primeRcConductorTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    return vec3(0.0);
}

vec3 primeRcConductorEnergy(vec3 wi, PrimeRcState state) {
    if (wi.z < 0.0) { return vec3(0.0); }
    if (primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        if ((state.samplingFlags & PRIME_RC_FLAG_DELTA_REFLECTION) == 0u) {
            return vec3(0.0);
        }
    } else if ((state.samplingFlags & PRIME_RC_FLAG_SPECULAR_REFLECTION) == 0u) {
        return vec3(0.0);
    }
    vec3 f0 = state.conductorFresnel.energyF0;
    return primeRcMicrofacetDirectionalAlbedoReflection(
            state.specularMicrofacet, wi.z, f0,
            clamp(vec3(state.conductorFresnel.weight), vec3(0.0), vec3(1.0)))
            * (vec3(1.0) + f0 * (1.0 - state.conductorEss) / state.conductorEss);
}

PrimeRcThroughput primeRcCoatEval(vec3 wi, vec3 wo, PrimeRcState state) {
    wi = primeRcOnbToLocal(state.coatLocalOnb, wi);
    wo = primeRcOnbToLocal(state.coatLocalOnb, wo);
    wo.z = abs(wo.z);
    PrimeRcReflectiveEvalBase base = primeRcReflectiveEvalBase(
            wi, wo, state.coatMicrofacet, state.samplingFlags, PRIME_RC_FLAG_REFLECTION);
    PrimeRcThroughput result;
    result.value = vec3(primeRcDielectricUnpolarized(
            state.coatFresnelIor, base.microCosine) * base.factor);
    result.flags = base.flags;
    if (primeRcIsNonDelta(result.flags)) {
        result.value /= primeRcReduceSum(primeRcMicrofacetDirectionalAlbedoTransmission(
                state.coatMicrofacet, wi.z, state.coatFresnelIor));
    }
    return result;
}

PrimeRcSampleResult primeRcCoatSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result = primeRcZeroSampleResult(state, stack);
    vec3 localWi = primeRcOnbToLocal(state.coatLocalOnb, wi);
    PrimeRcReflectiveSampleBase base = primeRcReflectiveSampleBase(
            localWi, randomValue, state.coatMicrofacet,
            state.samplingFlags, PRIME_RC_FLAG_REFLECTION);
    result.bsdfSample = base.bsdfSample;
    result.bsdfSample.throughput.value *= primeRcDielectricUnpolarized(
            state.coatFresnelIor, base.microCosine);
    if (primeRcIsNonDelta(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value /= primeRcReduceSum(
                primeRcMicrofacetDirectionalAlbedoTransmission(
                        state.coatMicrofacet, localWi.z, state.coatFresnelIor));
    }
    if (result.bsdfSample.throughput.flags != PRIME_RC_FLAG_NONE) {
        result.bsdfSample.wo = primeRcOnbToWorld(state.coatLocalOnb, result.bsdfSample.wo);
    }
    return result;
}

float primeRcCoatPdf(vec3 wi, vec3 wo, PrimeRcState state) {
    wi = primeRcOnbToLocal(state.coatLocalOnb, wi);
    wo = primeRcOnbToLocal(state.coatLocalOnb, wo);
    wo.z = abs(wo.z);
    return primeRcReflectivePdf(
            wi, wo, state.coatMicrofacet,
            state.samplingFlags, PRIME_RC_FLAG_REFLECTION);
}

vec3 primeRcCoatTintOut(vec3 wo, PrimeRcState state) {
    wo = primeRcOnbToLocal(state.coatLocalOnb, wo);
    return primeRcCoatViewDependentAbsorption(
            state.coatTint, abs(wo.z), state.coatFresnelIor, -1.0);
}

vec3 primeRcCoatTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    wi = primeRcOnbToLocal(state.coatLocalOnb, wi);
    vec3 viewTint = primeRcCoatViewDependentAbsorption(
            state.coatTint, abs(wi.z), state.coatFresnelIor, 1.0);
    if (wi.z < 0.0) { return viewTint; }
    float kr = 1.0 - (1.0 - primeRcDielectricEnergy(state.coatFresnelIor))
            / primeRcSquare(state.coatFresnelIor);
    float ks = primeRcDielectricUnpolarized(state.coatFresnelIor, wi.z);
    float k = mix(ks, kr, state.coatBaseRoughness);
    vec3 darkeningTerm = vec3(
            baseEnergy.x == 1.0 ? 1.0 : (1.0 - k) / (1.0 - baseEnergy.x * k),
            baseEnergy.y == 1.0 ? 1.0 : (1.0 - k) / (1.0 - baseEnergy.y * k),
            baseEnergy.z == 1.0 ? 1.0 : (1.0 - k) / (1.0 - baseEnergy.z * k));
    vec3 tintIn = mix(vec3(1.0), darkeningTerm, vec3(state.coatDarkening)) * viewTint;
    vec2 energy = primeRcMicrofacetDirectionalAlbedoTransmission(
            state.coatMicrofacet, wi.z, state.coatFresnelIor);
    return (energy.y / primeRcReduceSum(energy)) * tintIn;
}

vec3 primeRcCoatEnergy(vec3 wi, PrimeRcState state) {
    wi = primeRcOnbToLocal(state.coatLocalOnb, wi);
    if (wi.z < 0.0) { return vec3(0.0); }
    if (primeRcMicrofacetEffectivelySmooth(state.coatMicrofacet)) {
        if ((state.samplingFlags & PRIME_RC_FLAG_DELTA_REFLECTION) == 0u) {
            return vec3(0.0);
        }
    } else if ((state.samplingFlags & PRIME_RC_FLAG_SPECULAR_REFLECTION) == 0u) {
        return vec3(0.0);
    }
    vec2 energy = primeRcMicrofacetDirectionalAlbedoTransmission(
            state.coatMicrofacet, wi.z, state.coatFresnelIor);
    return vec3(energy.x / primeRcReduceSum(energy));
}

float primeRcFuzzDirectionalAlbedo(float x, float y) {
    float s = y * (0.0206607 + 1.58491 * y) / (0.0379424 + y * (1.32227 + y));
    float m = y * (-0.193854 + y * (-1.14885 + y * (1.7932 - 0.95943 * y * y)))
            / (0.046391 + y);
    float o = y * (0.000654023 + (-0.0207818 + 0.119681 * y) * y)
            / (1.26264 + y * (-1.92021 + y));
    return exp(-0.5 * primeRcSquare((x - m) / s)) / (s * sqrt(2.0 * PRIME_RC_PI)) + o;
}

float primeRcFuzzLtcAInv(float x, float y) {
    return (2.58126 * x + 0.813703 * y) * y
            / (1.0 + 0.310327 * x * x + 2.60994 * x * y);
}

float primeRcFuzzLtcBInv(float x, float y) {
    return sqrt(1.0 - x) * (y - 1.0) * y * y * y
            / (0.0000254053 + 1.71228 * x - 1.71506 * x * y + 1.34174 * y * y);
}

mat3 primeRcFuzzBasis(vec3 wi) {
    vec3 x = vec3(wi.x, wi.y, 0.0);
    float lengthSquared = dot(x, x);
    if (lengthSquared > 0.0) {
        x *= inversesqrt(lengthSquared);
        return mat3(x, cross(vec3(0.0, 0.0, 1.0), x), vec3(0.0, 0.0, 1.0));
    }
    return mat3(1.0);
}

PrimeRcThroughput primeRcFuzzEval(vec3 wi, vec3 wo, PrimeRcState state) {
    if (wi.z < 0.0 || wo.z < 0.0
            || (state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return primeRcZeroThroughput();
    }
    float roughness = clamp(state.material.fuzz.roughness, 0.01, 1.0);
    vec3 w = transpose(primeRcFuzzBasis(wi)) * wo;
    float aInv = primeRcFuzzLtcAInv(wi.z, roughness);
    float bInv = primeRcFuzzLtcBInv(wi.z, roughness);
    vec3 transformed = vec3(
            aInv * w.x + bInv * w.z,
            aInv * w.y,
            w.z);
    float lengthSquared = dot(transformed, transformed);
    float directionalAlbedo = primeRcFuzzDirectionalAlbedo(wi.z, roughness);
    PrimeRcThroughput result;
    result.value = state.material.fuzz.color * (directionalAlbedo
            * max(transformed.z, 0.0) * PRIME_RC_INV_PI
            * primeRcSquare(aInv / lengthSquared));
    result.flags = PRIME_RC_FLAG_DIFFUSE_REFLECTION;
    return result;
}

PrimeRcSampleResult primeRcFuzzSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result = primeRcZeroSampleResult(state, stack);
    if (wi.z < 0.0
            || (state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return result;
    }
    float roughness = clamp(state.material.fuzz.roughness, 0.01, 1.0);
    vec3 original = primeRcCosineSampleHemisphere(randomValue.xy);
    float aInv = primeRcFuzzLtcAInv(wi.z, roughness);
    float bInv = primeRcFuzzLtcBInv(wi.z, roughness);
    vec3 w = vec3(
            original.x / aInv - original.z * bInv / aInv,
            original.y / aInv,
            original.z);
    float lengthSquared = dot(w, w);
    w *= inversesqrt(lengthSquared);
    result.bsdfSample.wo = primeRcFuzzBasis(wi) * w;
    if (result.bsdfSample.wo.z <= 0.0) {
        result.bsdfSample.wo = vec3(0.0);
        return result;
    }
    result.bsdfSample.pdf = max(w.z, 0.0) * PRIME_RC_INV_PI
            * primeRcSquare(aInv * lengthSquared);
    float directionalAlbedo = primeRcFuzzDirectionalAlbedo(wi.z, roughness);
    vec3 transformed = vec3(aInv * w.x + bInv * w.z, aInv * w.y, w.z);
    lengthSquared = dot(transformed, transformed);
    result.bsdfSample.throughput.value = state.material.fuzz.color * (directionalAlbedo
            * max(transformed.z, 0.0) * PRIME_RC_INV_PI
            * primeRcSquare(aInv / lengthSquared));
    result.bsdfSample.throughput.flags = PRIME_RC_FLAG_DIFFUSE_REFLECTION;
    return result;
}

float primeRcFuzzPdf(vec3 wi, vec3 wo, PrimeRcState state) {
    if (wi.z < 0.0 || wo.z <= 0.0) { return 0.0; }
    float roughness = clamp(state.material.fuzz.roughness, 0.01, 1.0);
    vec3 w = transpose(primeRcFuzzBasis(wi)) * wo;
    float aInv = primeRcFuzzLtcAInv(wi.z, roughness);
    return max(w.z, 0.0) * PRIME_RC_INV_PI
            * primeRcSquare(aInv * dot(w, w));
}

vec3 primeRcFuzzTintOut(vec3 wo, PrimeRcState state) { return vec3(1.0); }
vec3 primeRcFuzzTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    if (wi.z < 0.0) { return vec3(1.0); }
    float roughness = clamp(state.material.fuzz.roughness, 0.01, 1.0);
    return vec3(1.0 - primeRcFuzzDirectionalAlbedo(wi.z, roughness));
}

vec3 primeRcFuzzEnergy(vec3 wi, PrimeRcState state) {
    if ((state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u) {
        return vec3(0.0);
    }
    float roughness = clamp(state.material.fuzz.roughness, 0.01, 1.0);
    return state.material.fuzz.color
            * primeRcFuzzDirectionalAlbedo(wi.z, roughness);
}

PrimeRcThroughput primeRcSubsurfaceEval(vec3 wi, vec3 wo, PrimeRcState state) {
    bool reflected = wo.z * wi.z > 0.0;
    if ((reflected && ((state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) == 0u
            || state.geometryThinWalled == 0u))
            || (!reflected
            && (state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_TRANSMISSION) == 0u)) {
        return primeRcZeroThroughput();
    }
    PrimeRcThroughput result;
    result.value = vec3(PRIME_RC_INV_PI * abs(wo.z));
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

PrimeRcSampleResult primeRcSubsurfaceSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
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
    result.bsdfSample.throughput.value = vec3(PRIME_RC_INV_PI * result.bsdfSample.wo.z);
    if (wi.z < 0.0) { result.bsdfSample.wo.z = -result.bsdfSample.wo.z; }
    if (randomValue.z < transmissionProbability) {
        result.bsdfSample.wo.z = -result.bsdfSample.wo.z;
        result.bsdfSample.pdf *= transmissionProbability;
        result.bsdfSample.throughput.value *= transmissionProbability;
        result.bsdfSample.throughput.flags = PRIME_RC_FLAG_DIFFUSE_TRANSMISSION;
        if (state.geometryThinWalled == 0u) {
            if (wi.z >= 0.0) {
                PrimeRcVolume volume = primeRcVolumeFromSubsurface(state.material.subsurface);
                volume.ior = state.originalIor;
                primeRcStackPush(result.volumeStack, volume);
            } else if (result.volumeStack.count == 0u) {
                result.bsdfSample.throughput.value *= state.material.subsurface.color;
            } else {
                primeRcStackPop(result.volumeStack);
            }
        } else {
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

float primeRcSubsurfacePdf(vec3 wi, vec3 wo, PrimeRcState state) {
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

vec3 primeRcSubsurfaceTintOut(vec3 wo, PrimeRcState state) { return vec3(0.0); }
vec3 primeRcSubsurfaceTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    return vec3(0.0);
}
vec3 primeRcSubsurfaceEnergy(vec3 wi, PrimeRcState state) {
    if ((state.samplingFlags & PRIME_RC_FLAG_DIFFUSE_REFLECTION) != 0u) {
        return state.geometryThinWalled == 0u
                ? vec3(1.0) : state.material.subsurface.color;
    }
    return vec3(0.0);
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
    vec3 absorption = state.transmissionTint
            * exp(-state.transmissionVolume.extinction / cosineThetaI);
    vec3 inverseSeries = 1.0 / (vec3(1.0) - primeRcSquare(fresnel.first * absorption));
    PrimeRcVecPair result;
    result.first = fresnel.first
            * (vec3(1.0) + primeRcSquare(fresnel.second * absorption) * inverseSeries);
    result.second = primeRcSquare(fresnel.second) * absorption * inverseSeries;
    return result;
}

vec3 primeRcCalculateThinWallDeltaTransmission(PrimeRcMaterial material, float cosineTheta) {
    float ior = primeRcIorAdjustment(material.specular.ior, material.weight.specular);
    vec3 reflection = vec3(primeRcDielectricUnpolarized(ior, cosineTheta));
    vec3 transmission = vec3(1.0) - reflection;
    float t0 = 1.0 - (1.0 - cosineTheta * cosineTheta) / primeRcSquare(ior);
    if (t0 <= 0.0) { return transmission; }
    float cosineThetaI = sqrt(t0);
    vec3 absorption;
    if (material.transmission.depth == 0.0) {
        absorption = material.transmission.color;
    } else {
        absorption = exp(-primeRcVolumeFromTransmission(material.transmission).extinction
                / cosineThetaI);
    }
    vec3 inverseSeries = 1.0 / (vec3(1.0) - primeRcSquare(reflection * absorption));
    return primeRcSquare(transmission) * absorption * inverseSeries;
}

PrimeRcThroughput primeRcTransmissionEval(vec3 wi, vec3 inputWo, PrimeRcState state) {
    if (state.geometryThinWalled != 0u) {
        vec3 wo = inputWo;
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
        result.value = vec3(base.factor);
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
    PrimeRcThroughput result = primeRcRefractiveEval(wi, inputWo, state);
    if (result.flags != PRIME_RC_FLAG_NONE) {
        if (primeRcIsTransmissive(result.flags)) {
            result.value *= state.transmissionTint
                    * exp(-state.transmissionVolume.extinction * state.rayT);
        }
        if (primeRcIsNonDelta(result.flags)) {
            result.value /= primeRcReduceSum(
                    primeRcMicrofacetDirectionalAlbedoTransmission(
                            state.specularMicrofacet, wi.z,
                            state.specularFresnel.ior));
        }
    }
    return result;
}

PrimeRcSampleResult primeRcTransmissionSample(
        vec3 wi, vec3 randomValue, PrimeRcState state, PrimeRcVolumeStack stack) {
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

    result.bsdfSample = primeRcRefractiveSample(wi, randomValue, state);
    if (result.bsdfSample.throughput.flags == PRIME_RC_FLAG_NONE) { return result; }
    if (primeRcIsTransmissive(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value *= state.transmissionTint;
        if (wi.z >= 0.0) {
            PrimeRcVolume volume = state.transmissionVolume;
            volume.ior = state.originalIor;
            primeRcStackPush(result.volumeStack, volume);
        } else if (result.volumeStack.count == 0u) {
            result.bsdfSample.throughput.value *= exp(
                    -state.transmissionVolume.extinction * state.rayT);
        } else {
            result.rayT = 0.0;
            primeRcStackPop(result.volumeStack);
        }
    } else if (wi.z <= 0.0 && result.volumeStack.count == 0u) {
        result.bsdfSample.throughput.value *= exp(
                -state.transmissionVolume.extinction * state.rayT);
        PrimeRcVolume volume = state.transmissionVolume;
        volume.ior = state.originalIor;
        primeRcStackPush(result.volumeStack, volume);
    }
    if (primeRcIsNonDelta(result.bsdfSample.throughput.flags)) {
        result.bsdfSample.throughput.value /= primeRcReduceSum(
                primeRcMicrofacetDirectionalAlbedoTransmission(
                        state.specularMicrofacet, wi.z,
                        state.specularFresnel.ior));
    }
    return result;
}

float primeRcTransmissionPdf(vec3 wi, vec3 inputWo, PrimeRcState state) {
    if (state.geometryThinWalled != 0u) {
        vec3 wo = inputWo;
        bool transmitted = false;
        if (wo.z < 0.0) {
            wo.z = -wo.z;
            transmitted = true;
        }
        vec3 halfVector = normalize(wi + wo);
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
    return primeRcRefractivePdf(wi, inputWo, state);
}

vec3 primeRcTransmissionTintOut(vec3 wo, PrimeRcState state) {
    return state.geometryThinWalled != 0u ? vec3(0.0) : vec3(1.0);
}

vec3 primeRcTransmissionTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    if (state.geometryThinWalled != 0u) { return vec3(0.0); }
    vec2 energy = primeRcMicrofacetDirectionalAlbedoTransmission(
            state.specularMicrofacet, wi.z, state.specularFresnel.ior);
    return vec3(energy.y / primeRcReduceSum(energy));
}

vec3 primeRcTransmissionEnergy(vec3 wi, PrimeRcState state) {
    if (!primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)
            && state.specularFresnel.ior != 1.0) {
        if (!primeRcIsSpecular(state.samplingFlags)) { return vec3(0.0); }
    } else if (!primeRcIsDelta(state.samplingFlags)) {
        return vec3(0.0);
    }
    if (state.geometryThinWalled != 0u) { wi.z = abs(wi.z); }
    vec2 energy = primeRcMicrofacetDirectionalAlbedoTransmission(
            state.specularMicrofacet, wi.z, state.specularFresnel.ior);
    float reflected = energy.x / primeRcReduceSum(energy);
    if (!primeRcIsReflective(state.samplingFlags)) { reflected = 0.0; }
    float transmitted = primeRcIsTransmissive(state.samplingFlags) ? 1.0 - reflected : 0.0;
    return reflected * state.specularFresnel.color
            + transmitted * state.transmissionTint;
}

float primeRcFactorial(int value) { return primeRcGamma(float(value + 1)); }

// Direct transcription of RoboCute's luisa/functions/math.hpp cylindrical-Bessel realization.
// Vulkan GLSL has no corresponding intrinsic, so these source formulas are part of the migrated
// model contract together with the diffraction lobe weights and sampling law below.
float primeRcBesselJSmall(int order, float value) {
    if (order >= 7) { return 0.0; }
    float x2 = value * value;
    float x4 = x2 * x2;
    float x6 = x2 * x4;
    float x8 = x4 * x4;
    float scale = 6.0 * pow(value / 2.0, float(order));
    float a = 1.0 / (6.0 * primeRcFactorial(order));
    float b = -x2 / (24.0 * primeRcFactorial(order + 1));
    float c1 = 11.0 * x8 - 864.0 * float(6 + order)
            * (x6 - 12.0 * float(5 + order)
            * (3.0 * x4 + 32.0 * float(4 + order)
            * (-2.0 * x2 + 27.0 * float(3 + order))));
    float c = c1 / primeRcFactorial(6 + order) * x4 * 5.652695401144601e-10;
    return scale * (a + b + c);
}

float primeRcBesselJLarge(int order, float value) {
    return sqrt((2.0 / PRIME_RC_PI) / value)
            * cos(value - float(order) * (PRIME_RC_PI / 2.0) - PRIME_RC_PI / 4.0);
}

float primeRcBesselJ(int inputOrder, float value) {
    float signValue = inputOrder > 0 || ((-inputOrder) % 2) == 0 ? 1.0 : -1.0;
    int order = abs(inputOrder);
    float cutoff = float(order) + 1.0;
    float interpolation = cutoff / 10.0;
    float large = primeRcBesselJLarge(order, value);
    if (order >= 7 || abs(value) >= cutoff + interpolation) {
        return large * signValue;
    }
    return mix(
            primeRcBesselJSmall(order, value),
            large,
            max(0.0, min(1.0,
                    0.5 * (value - cutoff - interpolation) / interpolation)))
            * signValue;
}

float primeRcSinc(float value) { return value == 0.0 ? 1.0 : sin(value) / value; }

float primeRcDiffractionAlpha(float cosineTheta, float height, float wavelengthNm) {
    float a = primeRcSquare(cosineTheta * height / wavelengthNm * (2.0 * PRIME_RC_PI));
    return exp(-a);
}

float primeRcDiffractionLobeIntensity(
        ivec2 lobe, float cosineTheta, float wavelengthNm, PrimeRcState state) {
    PrimeRcDiffraction diffraction = state.material.diffraction;
    float height = 1.0e3 * diffraction.thickness;
    float a = 4.0 * PRIME_RC_PI * height / (wavelengthNm * cosineTheta);
    float x;
    float y;
    if (diffraction.kind == PRIME_RC_DIFFRACTION_SINUSOIDAL) {
        x = lobe.x == 0 ? 1.0 : primeRcBesselJ(lobe.x, a);
        y = lobe.y == 0 ? 1.0 : primeRcBesselJ(lobe.y, a);
    } else if (diffraction.kind == PRIME_RC_DIFFRACTION_RECTANGULAR) {
        x = lobe.x == 0 ? 1.0
                : sin(a / 2.0) * primeRcSinc(PRIME_RC_PI * float(lobe.x) / 2.0);
        y = lobe.y == 0 ? 1.0
                : sin(a / 2.0) * primeRcSinc(PRIME_RC_PI * float(lobe.y) / 2.0);
    } else {
        x = lobe.x == 0 ? 1.0 : 1.0 / sqrt(float(abs(lobe.x)));
        y = lobe.y == 0 ? 1.0 : 1.0 / sqrt(float(abs(lobe.y)));
    }
    float result = x * y;
    if (any(equal(diffraction.invPitch, vec2(0.0)))) { result *= result; }
    return result;
}

struct PrimeRcDiffractionLobeSample {
    ivec2 lobe;
    vec2 pdf;
};

PrimeRcDiffractionLobeSample primeRcDiffractionSampleLobe(
        float cosineTheta,
        float wavelengthNm,
        vec2 inputRandom,
        PrimeRcState state) {
    float intensity[7];
    float total = 0.0;
    uint count = min(state.material.diffraction.lobeCount, PRIME_RC_MAX_DIFFRACTION_LOBES);
    for (uint index = 0u; index < count; index++) {
        float value = primeRcDiffractionLobeIntensity(
                ivec2(int(index + 1u), 0), cosineTheta, wavelengthNm, state);
        total += value;
        intensity[index] = value;
    }
    vec2 randomValue = (inputRandom - 0.5) * 2.0;
    float cdf = 0.0;
    ivec2 lobe = ivec2(0);
    vec2 pdf = vec2(0.0);
    for (uint index = 0u; index < count; index++) {
        float probability = intensity[index] / total;
        cdf += probability;
        bvec2 belowCdf = lessThan(abs(randomValue), vec2(cdf));
        bvec2 unselected = equal(lobe, ivec2(0));
        bvec2 choose = bvec2(
                belowCdf.x && unselected.x,
                belowCdf.y && unselected.y);
        pdf = mix(pdf, vec2(probability), choose);
        lobe = mix(lobe, ivec2(int(index + 1u)), choose);
    }
    pdf /= 2.0;
    lobe = mix(lobe, -lobe, lessThan(randomValue, vec2(0.0)));
    lobe = mix(lobe, ivec2(0), equal(state.material.diffraction.invPitch, vec2(0.0)));
    pdf = mix(pdf, vec2(1.0), equal(state.material.diffraction.invPitch, vec2(0.0)));
    PrimeRcDiffractionLobeSample result;
    result.lobe = lobe;
    result.pdf = pdf;
    return result;
}

vec3 primeRcDiffract(vec3 wi, ivec2 lobe, float wavelengthNm, vec2 inversePitch) {
    vec2 p = sqrt(wi.xy * wi.xy + wi.zz * wi.zz);
    vec2 sineIncident = vec2(
            p.x > 1.0e-6 ? wi.x / p.x : 0.0,
            p.y > 1.0e-6 ? wi.y / p.y : 0.0);
    vec2 sineOutgoing = 1.0e-3 * wavelengthNm * vec2(lobe) * inversePitch
            - sineIncident;
    float a = sineOutgoing.x;
    float b = sineOutgoing.y;
    float m = (a * a - 1.0) / (a * a * b * b - 1.0);
    float q = 1.0 - b * b * m;
    return normalize(vec3(
            a * sqrt(max(0.0, q)),
            b * sqrt(m),
            sqrt(max(0.0, 1.0 - a * a * q - b * b * m))));
}

PrimeRcThroughput primeRcDiffractionEval(vec3 wi, vec3 wo, PrimeRcState state) {
    return primeRcZeroThroughput();
}

PrimeRcSampleResult primeRcDiffractionSample(
        vec3 inputWi,
        vec3 randomValue,
        PrimeRcState state,
        PrimeRcVolumeStack stack) {
    PrimeRcSampleResult result = primeRcZeroSampleResult(state, stack);
    if (inputWi.z < 0.0
            || (state.samplingFlags & PRIME_RC_FLAG_DELTA_REFLECTION) == 0u) {
        return result;
    }
    vec3 wi = inputWi;
    vec2 cs = state.diffractionState.angleCs;
    wi.xy = vec2(cs.x * wi.x - cs.y * wi.y, cs.y * wi.x + cs.x * wi.y);
    float wavelength = state.wavelengthsNm[state.heroWavelengthIndex];
    PrimeRcDiffractionLobeSample lobe = primeRcDiffractionSampleLobe(
            wi.z, wavelength, randomValue.xy, state);
    vec3 wo = primeRcDiffract(
            wi, lobe.lobe, wavelength, state.material.diffraction.invPitch);
    if (!primeRcMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        wo = primeRcOnbToWorld(primeRcOnbFromNormal(state.diffractionState.h), wo);
    }
    if (wo.z < 0.0) { return result; }
    result.bsdfSample.pdf = max(2.0e-2, lobe.pdf.x * lobe.pdf.y);
    result.bsdfSample.throughput.value = state.material.diffraction.color
            * state.diffractionState.directionalAlbedo
            * primeRcDiffractionLobeIntensity(
                    lobe.lobe,
                    abs(dot(state.diffractionState.h, wo)),
                    wavelength,
                    state);
    result.bsdfSample.throughput.flags = PRIME_RC_FLAG_DELTA_REFLECTION;
    result.bsdfSample.wo = vec3(
            cs.x * wo.x + cs.y * wo.y,
            -cs.y * wo.x + cs.x * wo.y,
            wo.z);
    return result;
}

float primeRcDiffractionPdf(vec3 wi, vec3 wo, PrimeRcState state) { return 0.0; }
vec3 primeRcDiffractionTintOut(vec3 wo, PrimeRcState state) { return vec3(1.0); }
vec3 primeRcDiffractionTrans(vec3 wi, PrimeRcState state, vec3 baseEnergy) {
    return wi.z < 0.0
            ? vec3(1.0) : vec3(1.0 - state.diffractionState.directionalAlbedo);
}
vec3 primeRcDiffractionEnergy(vec3 wi, PrimeRcState state) {
    return (state.samplingFlags & PRIME_RC_FLAG_DELTA_REFLECTION) != 0u
            ? state.material.diffraction.color
                    * state.diffractionState.directionalAlbedo
            : vec3(0.0);
}

#endif
