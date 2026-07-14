#ifndef PRIME_BSDF_GLSL
#define PRIME_BSDF_GLSL

const float PRIME_PI = 3.14159265358979323846;

// These records are semantic contracts, not mega-kernel implementation details. Every color is
// linear Rec.2020 D65. A future wavefront backend may serialize the same inputs and outputs
// without changing either the estimator or this color-space meaning.
struct BsdfEvaluation {
    vec3 value;
    float pdf;
};

struct BsdfSample {
    vec3 direction;
    vec3 weight;
    float pdf;
    uint isDelta;
};

BsdfEvaluation primeEvaluateDiffuse(vec3 baseColor, vec3 normal, vec3 direction) {
    float cosine = max(dot(normal, direction), 0.0);
    BsdfEvaluation result;
    result.value = baseColor * (1.0 / PRIME_PI);
    result.pdf = cosine * (1.0 / PRIME_PI);
    return result;
}

BsdfSample primeSampleDiffuse(vec3 baseColor, vec3 normal, inout PathState path) {
    float radius = sqrt(primeRandom(path));
    float angle = 2.0 * PRIME_PI * primeRandom(path);
    vec3 localDirection = vec3(radius * cos(angle), radius * sin(angle),
            sqrt(max(0.0, 1.0 - radius * radius)));
    BsdfSample result;
    result.direction = primeLocalToWorld(localDirection, normal);
    result.pdf = max(dot(normal, result.direction), 0.0) * (1.0 / PRIME_PI);
    // f * cos(theta) / pdf simplifies exactly to the diffuse reflectance.
    result.weight = baseColor;
    result.isDelta = 0u;
    return result;
}

#endif
