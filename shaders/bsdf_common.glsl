#ifndef PRIME_BSDF_COMMON_GLSL
#define PRIME_BSDF_COMMON_GLSL

const float PRIME_PI = 3.14159265358979323846;

const uint PRIME_BSDF_EVENT_REFLECTION = 1u << 0u;
const uint PRIME_BSDF_EVENT_TRANSMISSION = 1u << 1u;
const uint PRIME_BSDF_EVENT_DIFFUSE = 1u << 2u;
const uint PRIME_BSDF_EVENT_GLOSSY = 1u << 3u;
const uint PRIME_BSDF_EVENT_DELTA = 1u << 4u;

// BSDF directions always point away from the shading point. viewDirection points toward the
// previous path vertex and scatterDirection points toward the next one. All non-delta PDFs use
// solid angle at the next direction. response is f * abs(cos(theta)); division by the complete
// proposal PDF is delayed until path advancement so no adapter introduces a canceling cosine or
// an unbounded intermediate weight. This convention must survive a wavefront split.
struct BsdfEvaluation {
    vec3 response;
    float pdf;
};

struct BsdfSample {
    vec3 direction;
    vec3 response;
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
    result.response = vec3(0.0);
    result.pdf = 0.0;
    return result;
}

BsdfSample primeInvalidBsdfSample() {
    BsdfSample result;
    result.direction = vec3(0.0);
    result.response = vec3(0.0);
    result.pdf = 0.0;
    result.relativeEta = 1.0;
    result.eventFlags = 0u;
    return result;
}

#endif
