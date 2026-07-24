#ifndef PRIME_NRD_COMMON_GLSL
#define PRIME_NRD_COMMON_GLSL

#include "default_material.glsl"

// These encoders are the shader half of the immutable NRD 4.17 contract declared in abi.json.
// Every radiance value is demodulated linear Rec.2020 D65. Normals remain in Prime's world axes,
// roughness is linear, motion XY stores FP16 pixel-space previous-minus-current before NRD's
// reciprocal render-size scale, and view Z is positive in Minecraft block units. Changing any
// one side requires rebuilding the bundled native library.

const float PRIME_NRD_FP16_MAX = 65504.0;
const float PRIME_NRD_MAX_SURFACE_DISTANCE = 65503.0;
const vec3 PRIME_NRD_HIT_DISTANCE_PARAMETERS = vec3(3.0, 0.1, 20.0);

bool primeNrdIsFinite(float value) {
    return !isnan(value) && !isinf(value);
}

bool primeNrdIsFinite(vec2 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

bool primeNrdIsFinite(vec3 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

bool primeNrdIsFinite(vec4 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

float primeNrdSanitizeUnit(float value, float fallback) {
    return clamp(primeNrdIsFinite(value) ? value : fallback, 0.0, 1.0);
}

float primeNrdSanitizeHitDistance(float hitDistance) {
    // A non-finite secondary distance is conservatively a miss. Zero has a distinct NRD meaning
    // (no usable hit distance) and would create a false near reflector for a valid noisy signal.
    return primeNrdIsFinite(hitDistance)
            ? clamp(hitDistance, 0.0, PRIME_NRD_FP16_MAX)
            : PRIME_NRD_FP16_MAX;
}

float primeNrdSanitizePrimaryDistance(float primaryDistance, bool hasSurface) {
    return hasSurface && primeNrdIsFinite(primaryDistance) && primaryDistance >= 0.0
            ? min(primaryDistance, PRIME_NRD_MAX_SURFACE_DISTANCE)
            : -1.0;
}

vec3 primeNrdSafeNormalize(vec3 value, vec3 fallback) {
    float lengthSquared = dot(value, value);
    if (!primeNrdIsFinite(value)
            || !primeNrdIsFinite(lengthSquared)
            || !(lengthSquared > 1.0e-12)) {
        return fallback;
    }
    vec3 normalized = normalize(value);
    return primeNrdIsFinite(normalized) ? normalized : fallback;
}

vec3 primeNrdSanitizeMotion(vec3 motion) {
    return primeNrdIsFinite(motion)
            ? clamp(motion, vec3(-PRIME_NRD_FP16_MAX), vec3(PRIME_NRD_FP16_MAX))
            : vec3(0.0);
}

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

float primeNrdMaterialId(uint materialFlags) {
    // R10_G10_B10_A2_UNORM provides four exact classes. These categories deliberately describe
    // reconstruction behavior, not every authored material: ordinary dielectrics, conductors,
    // transmissive interfaces, and strand-like foliage must not exchange temporal history.
    if (primeMaterialIsFoliage(materialFlags)) {
        return 1.0;
    }
    if (primeMaterialIsTransmissive(materialFlags)) {
        return 2.0 / 3.0;
    }
    return (materialFlags & PRIME_MATERIAL_FLAG_LABPBR_METAL) != 0u ? 1.0 / 3.0 : 0.0;
}

vec4 primeNrdPackNormalRoughness(vec3 normal, float roughness, float materialId) {
    vec3 encodedNormal = primeNrdSafeNormalize(normal, vec3(0.0, 0.0, 1.0));
    encodedNormal /= max(abs(encodedNormal.x) + abs(encodedNormal.y) + abs(encodedNormal.z), 1.0e-9);
    vec3 packed;
    packed.y = encodedNormal.y * 0.5 + 0.5;
    packed.x = encodedNormal.x * 0.5 + packed.y;
    packed.y -= encodedNormal.x * 0.5;
    float safeRoughness = primeNrdSanitizeUnit(
            roughness, PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS);
    float signedRoughness = encodedNormal.z < 0.0
            ? -max(safeRoughness, 1.5 / 512.0)
            : max(safeRoughness, 1.5 / 512.0);
    packed.z = signedRoughness * 0.5 + 0.5;
    return vec4(packed, primeNrdSanitizeUnit(materialId, 0.0));
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
    normal = primeNrdSafeNormalize(vec3(octahedral, z), vec3(0.0, 0.0, 1.0));
    roughness = primeNrdSanitizeUnit(abs(signedRoughness),
            PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS);
}

float primeNrdNormalizedHitDistance(float hitDistance, float viewZ, float roughness) {
    // Exact REBLUR_FrontEnd_GetNormHitDist contract. The roughness-dependent scale is essential
    // for specular virtual motion; using the diffuse shortcut here destabilizes highlights.
    hitDistance = primeNrdSanitizeHitDistance(hitDistance);
    viewZ = primeNrdIsFinite(viewZ) ? abs(viewZ) : PRIME_NRD_FP16_MAX;
    roughness = primeNrdSanitizeUnit(roughness, PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS);
    float spread = 1.0 - exp2(-200.0 * roughness * roughness);
    spread *= pow(roughness, 0.5);
    float scale = (PRIME_NRD_HIT_DISTANCE_PARAMETERS.x
            + abs(viewZ) * PRIME_NRD_HIT_DISTANCE_PARAMETERS.y)
            * mix(PRIME_NRD_HIT_DISTANCE_PARAMETERS.z, 1.0, spread);
    return clamp(hitDistance / max(scale, 1.0e-6), 0.0, 1.0);
}

float primeNrdRadiancePeak(vec3 radiance) {
    return max(radiance.x, max(radiance.y, radiance.z));
}

float primeNrdSanitizeNonnegative(float value) {
    return primeNrdIsFinite(value) ? max(value, 0.0) : 0.0;
}

vec3 primeNrdClampRadiance(vec3 radiance, float limit) {
    // Channels are independent estimators. One failed component must not erase the other two.
    radiance = vec3(
            primeNrdSanitizeNonnegative(radiance.x),
            primeNrdSanitizeNonnegative(radiance.y),
            primeNrdSanitizeNonnegative(radiance.z));
    float peak = primeNrdRadiancePeak(radiance);
    vec3 result = peak > limit ? radiance * (limit / peak) : radiance;
    // Multiplication may round one ulp above the target even though the scale is <= 1.
    return min(result, vec3(limit));
}

vec3 primeNrdSanitizeRadiance(vec3 radiance) {
    return primeNrdClampRadiance(radiance, PRIME_NRD_FP16_MAX);
}

vec3 primeNrdSanitizeAlbedo(vec3 albedo) {
    return vec3(
            primeNrdSanitizeUnit(albedo.x, 0.0),
            primeNrdSanitizeUnit(albedo.y, 0.0),
            primeNrdSanitizeUnit(albedo.z, 0.0));
}

float primeNrdDemodulateChannel(float radiance, float guide) {
    if (!(guide > 0.0) || !(radiance > 0.0)) return 0.0;
    // Select the clamped result before division so a valid tiny guide cannot create infinity and
    // then be mistaken for a failed channel by a later sanitizer.
    return radiance > PRIME_NRD_FP16_MAX * guide
            ? PRIME_NRD_FP16_MAX
            : radiance / guide;
}

vec3 primeNrdDemodulate(vec3 radiance, vec3 guide) {
    radiance = primeNrdSanitizeRadiance(radiance);
    guide = primeNrdSanitizeAlbedo(guide);
    return vec3(
            primeNrdDemodulateChannel(radiance.x, guide.x),
            primeNrdDemodulateChannel(radiance.y, guide.y),
            primeNrdDemodulateChannel(radiance.z, guide.z));
}

float primeNrdClampRadianceTriple(
        inout vec3 first,
        inout vec3 second,
        inout vec3 third,
        float limit) {
    first = vec3(
            primeNrdSanitizeNonnegative(first.x),
            primeNrdSanitizeNonnegative(first.y),
            primeNrdSanitizeNonnegative(first.z));
    second = vec3(
            primeNrdSanitizeNonnegative(second.x),
            primeNrdSanitizeNonnegative(second.y),
            primeNrdSanitizeNonnegative(second.z));
    third = vec3(
            primeNrdSanitizeNonnegative(third.x),
            primeNrdSanitizeNonnegative(third.y),
            primeNrdSanitizeNonnegative(third.z));

    float sourcePeak = max(
            primeNrdRadiancePeak(first),
            max(primeNrdRadiancePeak(second), primeNrdRadiancePeak(third)));
    if (!(sourcePeak > 0.0)) {
        return 1.0;
    }

    vec3 normalizedTotal = first / sourcePeak + second / sourcePeak + third / sourcePeak;
    float normalizedPeak = primeNrdRadiancePeak(normalizedTotal);
    float scale = min(1.0, (limit / sourcePeak) / max(normalizedPeak, 1.0e-20));
    first *= scale;
    second *= scale;
    third *= scale;
    return scale;
}

float primeNrdClampRadiancePair(
        inout vec3 first,
        inout vec3 second,
        float limit) {
    vec3 unused = vec3(0.0);
    return primeNrdClampRadianceTriple(first, second, unused, limit);
}

vec4 primeNrdPackRadianceAndHitDistance(vec3 radiance, float normalizedHitDistance) {
    vec3 sanitized = primeNrdSanitizeRadiance(radiance);
    return vec4(
            primeNrdLinearToYCoCg(sanitized),
            primeNrdSanitizeUnit(normalizedHitDistance, 0.0));
}

#endif
