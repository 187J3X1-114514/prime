#ifndef PRIME_BSDF_DIFFUSE_GLSL
#define PRIME_BSDF_DIFFUSE_GLSL

#include "bsdf_common.glsl"

BsdfEvaluation primeEvaluateDiffuseReflection(
        vec3 reflectance,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection) {
    float cosineView = dot(normal, viewDirection);
    float cosineScatter = dot(normal, scatterDirection);
    if (cosineView <= 0.0 || cosineScatter <= 0.0) {
        return primeInvalidBsdfEvaluation();
    }
    BsdfEvaluation result;
    result.value = max(reflectance, vec3(0.0)) * PRIME_INV_PI;
    result.pdf = cosineScatter * PRIME_INV_PI;
    return result;
}

BsdfSample primeSampleDiffuseReflection(
        vec3 reflectance,
        vec3 normal,
        vec3 viewDirection,
        vec2 sampleValue) {
    if (dot(normal, viewDirection) <= 0.0) {
        return primeInvalidBsdfSample();
    }
    BsdfSample result;
    result.direction = primeLocalToWorld(primeCosineSampleHemisphere(sampleValue), normal);
    result.pdf = max(dot(normal, result.direction), 0.0) * PRIME_INV_PI;
    result.weight = max(reflectance, vec3(0.0));
    result.relativeEta = 1.0;
    result.eventFlags = PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_DIFFUSE;
    return result;
}

BsdfEvaluation primeEvaluateDiffuseTransmission(
        vec3 transmittance,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection) {
    float cosineView = dot(normal, viewDirection);
    float cosineScatter = dot(normal, scatterDirection);
    if (cosineView <= 0.0 || cosineScatter >= 0.0) {
        return primeInvalidBsdfEvaluation();
    }
    BsdfEvaluation result;
    result.value = max(transmittance, vec3(0.0)) * PRIME_INV_PI;
    result.pdf = -cosineScatter * PRIME_INV_PI;
    return result;
}

BsdfSample primeSampleDiffuseTransmission(
        vec3 transmittance,
        vec3 normal,
        vec3 viewDirection,
        vec2 sampleValue) {
    if (dot(normal, viewDirection) <= 0.0) {
        return primeInvalidBsdfSample();
    }
    vec3 localDirection = primeCosineSampleHemisphere(sampleValue);
    localDirection.z = -localDirection.z;
    BsdfSample result;
    result.direction = primeLocalToWorld(localDirection, normal);
    result.pdf = max(-dot(normal, result.direction), 0.0) * PRIME_INV_PI;
    result.weight = max(transmittance, vec3(0.0));
    result.relativeEta = 1.0;
    result.eventFlags = PRIME_BSDF_EVENT_TRANSMISSION | PRIME_BSDF_EVENT_DIFFUSE;
    return result;
}

// A two-sided diffuse sheet is the physically explicit thin-surface approximation for LabPBR's
// SSS amount. It is not a solid BSSRDF: solid blocks must use the volume random-walk primitives in
// bsdf_subsurface.glsl once the material decoder can supply a mean free path.
BsdfEvaluation primeEvaluateThinSubsurface(
        vec3 albedo,
        float scatterAnisotropy,
        vec3 normal,
        vec3 viewDirection,
        vec3 scatterDirection) {
    float reflectionProbability = clamp(0.5 * (1.0 - scatterAnisotropy), 0.0, 1.0);
    float branchProbability = dot(normal, scatterDirection) > 0.0
            ? reflectionProbability
            : 1.0 - reflectionProbability;
    if (dot(normal, viewDirection) <= 0.0 || branchProbability <= 0.0) {
        return primeInvalidBsdfEvaluation();
    }
    BsdfEvaluation result;
    result.value = max(albedo, vec3(0.0)) * (branchProbability * PRIME_INV_PI);
    result.pdf = abs(dot(normal, scatterDirection)) * branchProbability * PRIME_INV_PI;
    return result;
}

BsdfSample primeSampleThinSubsurface(
        vec3 albedo,
        float scatterAnisotropy,
        vec3 normal,
        vec3 viewDirection,
        vec3 sampleValue) {
    if (dot(normal, viewDirection) <= 0.0) {
        return primeInvalidBsdfSample();
    }
    float reflectionProbability = clamp(0.5 * (1.0 - scatterAnisotropy), 0.0, 1.0);
    bool reflection = sampleValue.z < reflectionProbability;
    float branchProbability = reflection ? reflectionProbability : 1.0 - reflectionProbability;
    if (branchProbability <= 0.0) {
        return primeInvalidBsdfSample();
    }
    vec3 localDirection = primeCosineSampleHemisphere(sampleValue.xy);
    localDirection.z *= reflection ? 1.0 : -1.0;
    BsdfSample result;
    result.direction = primeLocalToWorld(localDirection, normal);
    result.pdf = abs(dot(normal, result.direction)) * branchProbability * PRIME_INV_PI;
    result.weight = max(albedo, vec3(0.0));
    result.relativeEta = 1.0;
    result.eventFlags = (reflection
            ? PRIME_BSDF_EVENT_REFLECTION
            : PRIME_BSDF_EVENT_TRANSMISSION) | PRIME_BSDF_EVENT_DIFFUSE;
    return result;
}

#endif
