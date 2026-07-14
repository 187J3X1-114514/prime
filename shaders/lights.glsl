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
    return primeAtmosphereSky(direction, integrator.sunDirectionIntensity.xyz);
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

float primeSunCosAngularRadius() {
    return cos(ATM_SUN_ANGULAR_RADIUS_RADIANS);
}

float primeSunSolidAngle() {
    // 4*pi*sin(radius/2)^2 is algebraically identical to 2*pi*(1-cos(radius))
    // but avoids subtracting two nearly equal f32 values for the real solar radius.
    float sineHalfRadius = sin(0.5 * ATM_SUN_ANGULAR_RADIUS_RADIANS);
    return 4.0 * PRIME_PI * sineHalfRadius * sineHalfRadius;
}

float primeSunPdf() {
    return 1.0 / max(primeSunSolidAngle(), 1.0e-12);
}

bool primeSunContainsDirection(IntegratorRecord integrator, vec3 direction) {
    return dot(normalize(direction), normalize(integrator.sunDirectionIntensity.xyz))
            >= primeSunCosAngularRadius();
}

vec3 primeSunRadiance(
        IntegratorRecord integrator,
        vec3 surfacePosition,
        vec3 direction) {
    return vec3(max(integrator.sunDirectionIntensity.w, 0.0) / primeSunSolidAngle())
            * primeAtmosphereSunTransmittance(surfacePosition, direction);
}

LightEvaluation primeEvaluateSun(
        IntegratorRecord integrator,
        vec3 surfacePosition,
        vec3 direction) {
    LightEvaluation result;
    bool containsDirection = primeSunContainsDirection(integrator, direction);
    result.radiance = containsDirection
            ? primeSunRadiance(integrator, surfacePosition, direction)
            : vec3(0.0);
    result.pdf = containsDirection ? primeSunPdf() : 0.0;
    return result;
}

LightSample primeSampleSun(
        IntegratorRecord integrator,
        vec3 surfacePosition,
        inout PathState path) {
    float cosine = mix(primeSunCosAngularRadius(), 1.0, primeRandom(path));
    float sine = sqrt(max(1.0 - cosine * cosine, 0.0));
    float azimuth = 2.0 * PRIME_PI * primeRandom(path);
    vec3 localDirection = vec3(sine * cos(azimuth), sine * sin(azimuth), cosine);
    LightSample result;
    result.direction = primeLocalToWorld(
            localDirection,
            normalize(integrator.sunDirectionIntensity.xyz));
    result.distance = 1000000.0;
    result.radiance = primeSunRadiance(integrator, surfacePosition, result.direction);
    result.pdf = primeSunPdf();
    result.isDelta = 0u;
    return result;
}

#endif
