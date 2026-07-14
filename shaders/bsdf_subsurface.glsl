#ifndef PRIME_BSDF_SUBSURFACE_GLSL
#define PRIME_BSDF_SUBSURFACE_GLSL

#include "bsdf_common.glsl"

float primeHenyeyGreenstein(float cosine, float anisotropy) {
    float g = clamp(anisotropy, -0.999, 0.999);
    float denominator = 1.0 + g * g - 2.0 * g * clamp(cosine, -1.0, 1.0);
    return (1.0 - g * g)
            / (4.0 * PRIME_PI * denominator * sqrt(max(denominator, PRIME_BSDF_EPSILON)));
}

PhaseEvaluation primeEvaluateHenyeyGreenstein(
        vec3 incidentDirection,
        vec3 scatterDirection,
        float anisotropy) {
    PhaseEvaluation result;
    result.value = primeHenyeyGreenstein(
            dot(normalize(incidentDirection), normalize(scatterDirection)), anisotropy);
    result.pdf = result.value;
    return result;
}

PhaseSample primeSampleHenyeyGreenstein(
        vec3 incidentDirection,
        float anisotropy,
        vec2 sampleValue) {
    float g = clamp(anisotropy, -0.999, 0.999);
    float cosine;
    if (abs(g) < 1.0e-3) {
        cosine = 1.0 - 2.0 * sampleValue.x;
    } else {
        float ratio = (1.0 - g * g) / (1.0 - g + 2.0 * g * sampleValue.x);
        cosine = (1.0 + g * g - ratio * ratio) / (2.0 * g);
    }
    cosine = clamp(cosine, -1.0, 1.0);
    float sine = sqrt(max(0.0, 1.0 - cosine * cosine));
    float azimuth = 2.0 * PRIME_PI * sampleValue.y;
    PhaseSample result;
    result.direction = primeLocalToWorld(
            vec3(sine * cos(azimuth), sine * sin(azimuth), cosine),
            normalize(incidentDirection));
    result.pdf = primeHenyeyGreenstein(cosine, g);
    result.weight = result.pdf > 0.0 ? 1.0 : 0.0;
    return result;
}

float primeSampleHomogeneousFreeFlight(float extinction, float sampleValue) {
    float sigmaT = max(extinction, PRIME_BSDF_EPSILON);
    return -log(max(1.0 - sampleValue, PRIME_BSDF_EPSILON)) / sigmaT;
}

float primeHomogeneousFreeFlightPdf(float extinction, float distance) {
    float sigmaT = max(extinction, 0.0);
    return sigmaT * exp(-sigmaT * max(distance, 0.0));
}

#endif
