#ifndef PRIME_BSDF_EMISSION_GLSL
#define PRIME_BSDF_EMISSION_GLSL

#include "bsdf_common.glsl"

EdfEvaluation primeEvaluateDiffuseEmission(
        vec3 radiance,
        vec3 normal,
        vec3 direction) {
    EdfEvaluation result;
    float cosine = max(dot(normal, direction), 0.0);
    result.radiance = cosine > 0.0 ? max(radiance, vec3(0.0)) : vec3(0.0);
    result.pdf = cosine * PRIME_INV_PI;
    return result;
}

EdfSample primeSampleDiffuseEmission(
        vec3 radiance,
        vec3 normal,
        vec2 sampleValue) {
    EdfSample result;
    result.direction = primeLocalToWorld(primeCosineSampleHemisphere(sampleValue), normal);
    result.radiance = max(radiance, vec3(0.0));
    result.pdf = max(dot(normal, result.direction), 0.0) * PRIME_INV_PI;
    return result;
}

#endif
