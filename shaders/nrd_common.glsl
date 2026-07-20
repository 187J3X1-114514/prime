#ifndef PRIME_NRD_COMMON_GLSL
#define PRIME_NRD_COMMON_GLSL

// These encoders are the shader half of the immutable NRD 4.17 contract declared in abi.json.
// Every radiance value is demodulated linear Rec.2020 D65. Normals remain in Prime's world axes,
// roughness is linear, motion is non-jittered 2.5D screen-space previous-minus-current, and view Z
// is positive in Minecraft block units. Changing any one side requires rebuilding the bundled
// native library.

const float PRIME_NRD_FP16_MAX = 65504.0;
const float PRIME_NRD_RADIANCE_LIMIT = 16.0;
const vec3 PRIME_NRD_HIT_DISTANCE_PARAMETERS = vec3(3.0, 0.1, 20.0);

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

void primeNrdUnpackNormalRoughness(
        vec4 packedValue, out vec3 normal, out float roughness) {
    vec3 packed = packedValue.rgb;
    vec2 octahedral = vec2(
            packed.x - packed.y,
            packed.x + packed.y - 1.0);
    float signedRoughness = packed.z * 2.0 - 1.0;
    float z = sign(signedRoughness)
            * max(1.0 - abs(octahedral.x) - abs(octahedral.y), 0.0);
    normal = normalize(vec3(octahedral, z));
    roughness = abs(signedRoughness);
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

float primeNrdRadiancePeak(vec3 radiance) {
    return max(radiance.x, max(radiance.y, radiance.z));
}

vec3 primeNrdClampRadiance(vec3 radiance, float limit) {
    bool invalid = any(isnan(radiance)) || any(isinf(radiance));
    radiance = invalid ? vec3(0.0) : max(radiance, vec3(0.0));
    float peak = primeNrdRadiancePeak(radiance);
    return peak > limit ? radiance * (limit / peak) : radiance;
}

vec3 primeNrdSanitizeRadiance(vec3 radiance) {
    return primeNrdClampRadiance(radiance, PRIME_NRD_FP16_MAX);
}

vec3 primeNrdSanitizeGuide(vec3 guide) {
    bool invalid = any(isnan(guide)) || any(isinf(guide));
    return invalid ? vec3(0.0) : clamp(guide, vec3(0.0), vec3(PRIME_NRD_FP16_MAX));
}

vec3 primeNrdDemodulate(vec3 radiance, vec3 guide) {
    radiance = primeNrdSanitizeRadiance(radiance);
    guide = primeNrdSanitizeGuide(guide);
    return vec3(
            guide.x > 0.0 ? radiance.x / guide.x : 0.0,
            guide.y > 0.0 ? radiance.y / guide.y : 0.0,
            guide.z > 0.0 ? radiance.z / guide.z : 0.0);
}

void primeNrdClampRadianceTriple(
        inout vec3 first,
        inout vec3 second,
        inout vec3 third,
        float limit) {
    bool invalidFirst = any(isnan(first)) || any(isinf(first));
    bool invalidSecond = any(isnan(second)) || any(isinf(second));
    bool invalidThird = any(isnan(third)) || any(isinf(third));
    first = invalidFirst ? vec3(0.0) : max(first, vec3(0.0));
    second = invalidSecond ? vec3(0.0) : max(second, vec3(0.0));
    third = invalidThird ? vec3(0.0) : max(third, vec3(0.0));

    float sourcePeak = max(
            primeNrdRadiancePeak(first),
            max(primeNrdRadiancePeak(second), primeNrdRadiancePeak(third)));
    if (!(sourcePeak > 0.0)) {
        return;
    }

    vec3 normalizedTotal = first / sourcePeak + second / sourcePeak + third / sourcePeak;
    float normalizedPeak = primeNrdRadiancePeak(normalizedTotal);
    float scale = min(1.0, (limit / sourcePeak) / max(normalizedPeak, 1.0e-20));
    first *= scale;
    second *= scale;
    third *= scale;
}

void primeNrdClampRadiancePair(
        inout vec3 first,
        inout vec3 second,
        float limit) {
    vec3 unused = vec3(0.0);
    primeNrdClampRadianceTriple(first, second, unused, limit);
}

vec4 primeNrdPackRadianceAndHitDistance(vec3 radiance, float normalizedHitDistance) {
    vec3 sanitized = primeNrdSanitizeRadiance(radiance);
    return vec4(primeNrdLinearToYCoCg(sanitized), clamp(normalizedHitDistance, 0.0, 1.0));
}

#endif
