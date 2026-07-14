#ifndef PRIME_NRD_COMMON_GLSL
#define PRIME_NRD_COMMON_GLSL

// These encoders are the shader half of the immutable NRD 4.17 contract declared in abi.json.
// Every radiance value is demodulated linear Rec.2020 D65. Normals remain in Prime's world axes,
// roughness is linear, motion is non-jittered 2.5D screen-space previous-minus-current, and view Z
// is positive in Minecraft block units. Changing any one side requires rebuilding the bundled
// native library.

const float PRIME_NRD_FP16_MAX = 65504.0;
const vec3 PRIME_NRD_HIT_DISTANCE_PARAMETERS = vec3(3.0, 0.1, 20.0);
const float PRIME_NRD_MATERIAL_FACTOR_MIN = 0.02;
const float PRIME_NRD_ROUGHNESS_FACTOR_MIN = 0.1;
const float PRIME_NRD_EPSILON = 1.0e-6;

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

vec3 primeNrdEnvironmentTerm(vec3 reflectanceAtNormal, float cosineView, float roughness) {
    // Exact GLSL transcription of NRD 4.17.4's _NRD_EnvironmentTerm_Rtg. Matrix products are
    // written as row dot-products to preserve HLSL mul(M, v) semantics across languages.
    float squaredRoughness = clamp(roughness * roughness, 0.0, 1.0);
    vec4 x = vec4(1.0, cosineView, cosineView * cosineView, 0.0);
    x.w = x.y * x.z;
    vec4 y = vec4(
            1.0,
            squaredRoughness,
            squaredRoughness * squaredRoughness,
            0.0);
    y.w = y.y * y.z;

    vec2 m1x = vec2(
            dot(vec2(0.99044, -1.28514), x.xy),
            dot(vec2(1.29678, -0.755907), x.xy));
    vec3 m2x = vec3(
            dot(vec3(1.0, 2.92338, 59.4188), x.xyw),
            dot(vec3(20.3225, -27.0302, 222.592), x.xyw),
            dot(vec3(121.563, 626.13, 316.627), x.xyw));
    vec2 m3x = vec2(
            dot(vec2(0.0365463, 3.32707), x.xy),
            dot(vec2(9.0632, -9.04756), x.xy));
    vec3 m4x = vec3(
            dot(vec3(1.0, 3.59685, -1.36772), x.xzw),
            dot(vec3(9.04401, -16.3174, 9.22949), x.xzw),
            dot(vec3(5.56589, 19.7886, -20.2123), x.xzw));
    float bias = dot(m1x, y.xy) / max(dot(m2x, y.xyw), PRIME_NRD_EPSILON);
    float scale = dot(m3x, y.xy) / max(dot(m4x, y.xyw), PRIME_NRD_EPSILON);
    return clamp(reflectanceAtNormal * scale + bias, 0.0, 1.0);
}

void primeNrdMaterialFactors(
        vec3 normal,
        vec3 viewDirection,
        vec3 baseColor,
        vec3 reflectanceAtNormal,
        float roughness,
        out vec3 diffuseFactor,
        out vec3 specularFactor) {
    // Keep this paired with NRD_MaterialFactors. REBLUR expects illumination with both the
    // diffuse albedo and the view-dependent environment BRDF removed before filtering.
    float cosineView = abs(dot(normal, viewDirection));
    vec3 environmentFresnel = primeNrdEnvironmentTerm(
            reflectanceAtNormal, cosineView, roughness);
    diffuseFactor = mix(
            vec3(PRIME_NRD_MATERIAL_FACTOR_MIN),
            vec3(1.0),
            (vec3(1.0) - environmentFresnel) * clamp(baseColor, 0.0, 1.0));
    specularFactor = environmentFresnel
            * mix(PRIME_NRD_ROUGHNESS_FACTOR_MIN, 1.0, roughness);
    specularFactor = mix(
            vec3(PRIME_NRD_MATERIAL_FACTOR_MIN), vec3(1.0), specularFactor);
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

float primeNrdNormalizedHitDistance(float hitDistance, float viewZ, float roughness) {
    // Exact REBLUR_FrontEnd_GetNormHitDist contract. The roughness-dependent scale is essential
    // for specular virtual motion; using the diffuse shortcut here destabilizes highlights.
    float spread = 1.0 - exp2(-200.0 * roughness * roughness);
    spread *= pow(clamp(roughness, 0.0, 1.0), 0.5);
    float scale = (PRIME_NRD_HIT_DISTANCE_PARAMETERS.x
            + abs(viewZ) * PRIME_NRD_HIT_DISTANCE_PARAMETERS.y)
            * mix(PRIME_NRD_HIT_DISTANCE_PARAMETERS.z, 1.0, spread);
    return clamp(hitDistance / max(scale, 1.0e-6), 0.0, 1.0);
}

vec4 primeNrdPackRadianceAndHitDistance(vec3 radiance, float normalizedHitDistance) {
    bool invalid = any(isnan(radiance)) || any(isinf(radiance));
    vec3 sanitized = invalid ? vec3(0.0) : clamp(radiance, vec3(0.0), vec3(PRIME_NRD_FP16_MAX));
    return vec4(primeNrdLinearToYCoCg(sanitized), clamp(normalizedHitDistance, 0.0, 1.0));
}

#endif
