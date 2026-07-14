#ifndef PRIME_NRD_COMMON_GLSL
#define PRIME_NRD_COMMON_GLSL

// These encoders are the shader half of the immutable NRD 4.17 contract declared in abi.json.
// Every radiance value is demodulated linear Rec.2020 D65. Normals remain in Prime's world axes,
// roughness is linear, motion vectors are world-space previous-minus-current, and view Z is in
// Minecraft block units. Changing any one side requires rebuilding the bundled native library.

const float PRIME_NRD_FP16_MAX = 65504.0;
const vec3 PRIME_NRD_HIT_DISTANCE_PARAMETERS = vec3(3.0, 0.1, 20.0);
const float PRIME_NRD_LINEAR_ROUGHNESS = 0.8;
const float PRIME_NRD_MATERIAL_FACTOR_MIN = 0.02;

vec3 primeNrdLinearToYCoCg(vec3 color) {
    return vec3(
            dot(color, vec3(0.25, 0.5, 0.25)),
            dot(color, vec3(0.5, 0.0, -0.5)),
            dot(color, vec3(-0.25, 0.5, -0.25)));
}

vec3 primeNrdYCoCgToLinear(vec3 color) {
    float t = color.x - color.z;
    return max(vec3(t + color.y, color.x + color.z, t - color.y), vec3(0.0));
}

vec3 primeNrdMaterialFactor(vec3 baseColor) {
    return mix(vec3(PRIME_NRD_MATERIAL_FACTOR_MIN), vec3(1.0), clamp(baseColor, 0.0, 1.0));
}

vec4 primeNrdPackNormalRoughness(vec3 normal, float roughness) {
    vec3 encodedNormal = normalize(normal);
    encodedNormal /= max(abs(encodedNormal.x) + abs(encodedNormal.y) + abs(encodedNormal.z), 1.0e-9);
    vec3 packed;
    packed.y = encodedNormal.y * 0.5 + 0.5;
    packed.x = encodedNormal.x * 0.5 + packed.y;
    packed.y -= encodedNormal.x * 0.5;
    float signedRoughness = encodedNormal.z < 0.0
            ? -max(roughness, 1.5 / 512.0)
            : max(roughness, 1.5 / 512.0);
    packed.z = signedRoughness * 0.5 + 0.5;
    return vec4(packed, 0.0);
}

float primeNrdNormalizedDiffuseHitDistance(float hitDistance, float viewZ) {
    // Diffuse uses roughness=1. Its specular magic curve is therefore one to f32 precision and
    // the default normalization reduces exactly to A + abs(viewZ) * B.
    float scale = PRIME_NRD_HIT_DISTANCE_PARAMETERS.x
            + abs(viewZ) * PRIME_NRD_HIT_DISTANCE_PARAMETERS.y;
    return clamp(hitDistance / max(scale, 1.0e-6), 0.0, 1.0);
}

vec4 primeNrdPackDiffuseSignal(vec3 radiance, float normalizedHitDistance) {
    bool invalid = any(isnan(radiance)) || any(isinf(radiance));
    vec3 sanitized = invalid ? vec3(0.0) : clamp(radiance, vec3(0.0), vec3(PRIME_NRD_FP16_MAX));
    return vec4(primeNrdLinearToYCoCg(sanitized), clamp(normalizedHitDistance, 0.0, 1.0));
}

#endif
