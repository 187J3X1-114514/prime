#ifndef PRIME_LIGHTS_GLSL
#define PRIME_LIGHTS_GLSL

struct LightSample {
    vec3 direction;
    float distance;
    vec3 radiance;
    float pdf;
    uint isDelta;
};

struct LightEvaluation {
    vec3 radiance;
    float pdf;
};

// All radiance values in this adapter are linear Rec.2020 D65. pdf is the complete f32 sampling
// density, including light-selection probability when a future registry introduces one. Reverse
// PDF queries must reuse that exact quantized value. The current environment and sun adapters are
// evaluated separately and perform no selection.

float primePowerHeuristic(float firstPdf, float secondPdf) {
    float first = firstPdf * firstPdf;
    float second = secondPdf * secondPdf;
    return first / max(first + second, 1.0e-30);
}

vec3 primeEnvironmentRadiance(IntegratorRecord integrator, vec3 direction) {
    return integrator.environmentRadiance.rgb;
}

LightEvaluation primeEvaluateEnvironment(
        IntegratorRecord integrator,
        vec3 direction,
        float exactSamplingPdf) {
    LightEvaluation result;
    result.radiance = primeEnvironmentRadiance(integrator, direction);
    result.pdf = exactSamplingPdf;
    return result;
}

float primeEnvironmentPdf(vec3 normal, vec3 direction) {
    return dot(normal, direction) > 0.0 ? 1.0 / (2.0 * PRIME_PI) : 0.0;
}

LightSample primeSampleEnvironment(
        IntegratorRecord integrator,
        vec3 normal,
        inout PathState path) {
    float z = primeRandom(path);
    float angle = 2.0 * PRIME_PI * primeRandom(path);
    float radius = sqrt(max(0.0, 1.0 - z * z));
    LightSample result;
    result.direction = primeLocalToWorld(vec3(radius * cos(angle), radius * sin(angle), z), normal);
    result.distance = 1000000.0;
    result.radiance = primeEnvironmentRadiance(integrator, result.direction);
    result.pdf = 1.0 / (2.0 * PRIME_PI);
    result.isDelta = 0u;
    return result;
}

LightSample primeSampleSun(IntegratorRecord integrator) {
    LightSample result;
    result.direction = normalize(integrator.sunDirectionIntensity.xyz);
    result.distance = 1000000.0;
    result.radiance = vec3(max(integrator.sunDirectionIntensity.w, 0.0));
    result.pdf = 1.0;
    result.isDelta = 1u;
    return result;
}

#endif
