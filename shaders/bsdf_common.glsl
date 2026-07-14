#ifndef PRIME_BSDF_COMMON_GLSL
#define PRIME_BSDF_COMMON_GLSL

const float PRIME_PI = 3.14159265358979323846;
const float PRIME_INV_PI = 0.31830988618379067154;
const float PRIME_BSDF_EPSILON = 1.0e-7;

const uint PRIME_BSDF_EVENT_REFLECTION = 1u << 0u;
const uint PRIME_BSDF_EVENT_TRANSMISSION = 1u << 1u;
const uint PRIME_BSDF_EVENT_DIFFUSE = 1u << 2u;
const uint PRIME_BSDF_EVENT_GLOSSY = 1u << 3u;
const uint PRIME_BSDF_EVENT_DELTA = 1u << 4u;

// BSDF directions always point away from the shading point. viewDirection points toward the
// previous path vertex and scatterDirection points toward the next one. All non-delta PDFs use
// solid angle at the next direction. weight is exactly f * abs(cos(theta)) / pdf in radiance
// transport mode. This convention is shared by every closure and must survive a wavefront split.
struct BsdfEvaluation {
    vec3 value;
    float pdf;
};

struct BsdfSample {
    vec3 direction;
    vec3 weight;
    float pdf;
    float relativeEta;
    uint eventFlags;
};

struct EdfEvaluation {
    vec3 radiance;
    float pdf;
};

struct EdfSample {
    vec3 direction;
    vec3 radiance;
    float pdf;
};

struct PhaseEvaluation {
    float value;
    float pdf;
};

struct PhaseSample {
    vec3 direction;
    float weight;
    float pdf;
};

BsdfEvaluation primeInvalidBsdfEvaluation() {
    BsdfEvaluation result;
    result.value = vec3(0.0);
    result.pdf = 0.0;
    return result;
}

BsdfSample primeInvalidBsdfSample() {
    BsdfSample result;
    result.direction = vec3(0.0);
    result.weight = vec3(0.0);
    result.pdf = 0.0;
    result.relativeEta = 1.0;
    result.eventFlags = 0u;
    return result;
}

float primeRec2020Luminance(vec3 value) {
    return dot(max(value, vec3(0.0)), vec3(0.2627, 0.6780, 0.0593));
}

vec3 primeCosineSampleHemisphere(vec2 sampleValue) {
    float radius = sqrt(sampleValue.x);
    float azimuth = 2.0 * PRIME_PI * sampleValue.y;
    return vec3(radius * cos(azimuth), radius * sin(azimuth),
            sqrt(max(0.0, 1.0 - sampleValue.x)));
}

#endif
