#ifndef PRIME_LIGHT_TREE_MATH_GLSL
#define PRIME_LIGHT_TREE_MATH_GLSL

const uint PRIME_LIGHT_RECEIVER_NORMAL_ACTIVE = 0x8000u;
const float PRIME_LIGHT_RECEIVER_COSINE_FLOOR = 1.0 / 256.0;

vec2 primeLightTreeSignNotZero(vec2 value) {
    return vec2(value.x >= 0.0 ? 1.0 : -1.0, value.y >= 0.0 ? 1.0 : -1.0);
}

// The compact normal lives in an otherwise unused cold path lane. Forward sampling uses the
// decoded value too, so reverse MIS reconstructs the exact same quantized selection density.
uint primePackLightReceiverNormal(vec3 value) {
    vec3 normal = normalize(value);
    normal /= max(abs(normal.x) + abs(normal.y) + abs(normal.z), 1.0e-20);
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * primeLightTreeSignNotZero(normal.xy);
    }
    vec2 unit = clamp(normal.xy * 0.5 + 0.5, vec2(0.0), vec2(1.0));
    uvec2 quantized = uvec2(round(unit * vec2(255.0, 127.0)));
    return PRIME_LIGHT_RECEIVER_NORMAL_ACTIVE
            | quantized.x
            | (quantized.y << 8u);
}

vec3 primeUnpackLightReceiverNormal(uint packedValue) {
    if ((packedValue & PRIME_LIGHT_RECEIVER_NORMAL_ACTIVE) == 0u) {
        return vec3(0.0);
    }
    vec2 encoded = vec2(
            float(packedValue & 0xffu) / 255.0,
            float((packedValue >> 8u) & 0x7fu) / 127.0) * 2.0 - 1.0;
    vec3 normal = vec3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * primeLightTreeSignNotZero(normal.xy);
    }
    return normalize(normal);
}

float primeLightReceiverCosineBound(
        vec3 boundsMin,
        vec3 boundsMax,
        vec3 point,
        vec3 receiverNormal,
        float distanceSquared) {
    if (!(dot(receiverNormal, receiverNormal) > 0.0)) {
        return 1.0;
    }
    vec3 support = mix(
            boundsMin,
            boundsMax,
            greaterThanEqual(receiverNormal, vec3(0.0)));
    float maximumProjection = dot(receiverNormal, support - point);
    if (!(maximumProjection > 0.0)) {
        return PRIME_LIGHT_RECEIVER_COSINE_FLOOR;
    }
    float bound = distanceSquared > maximumProjection * maximumProjection
            ? maximumProjection * inversesqrt(distanceSquared)
            : 1.0;
    // Quantization and floating-point boundary cases must not remove support from a contributing
    // leaf. The floor only affects importance; the exact light PDF remains unbiased.
    return max(bound, PRIME_LIGHT_RECEIVER_COSINE_FLOOR);
}

vec3 primeLightNodeMetrics(
        vec4 boundsMinPower,
        vec4 boundsMaxSoftening,
        vec3 point,
        vec3 receiverNormal) {
    vec3 closest = clamp(point, boundsMinPower.xyz, boundsMaxSoftening.xyz);
    vec3 delta = point - closest;
    return vec3(
            dot(delta, delta) + boundsMaxSoftening.w,
            boundsMinPower.w,
            primeLightReceiverCosineBound(
                    boundsMinPower.xyz,
                    boundsMaxSoftening.xyz,
                    point,
                    receiverNormal,
                    dot(delta, delta)));
}

float primeLightBranchProbability(vec3 firstMetrics, vec3 secondMetrics) {
    // Keep this order identical for forward selection and reverse MIS reconstruction.
    float firstDistanceSquared = firstMetrics.x;
    float secondDistanceSquared = secondMetrics.x;
    float firstPower = max(firstMetrics.y, 0.0);
    float secondPower = max(secondMetrics.y, 0.0);
    float powerScale = max(firstPower, secondPower);
    if (powerScale == 0.0) return -1.0;
    float firstPowerRatio = firstPower / powerScale;
    float secondPowerRatio = secondPower / powerScale;
    float firstImportance = max(firstMetrics.z, 0.0);
    float secondImportance = max(secondMetrics.z, 0.0);
    float distanceScale = max(firstDistanceSquared, secondDistanceSquared);
    if (distanceScale == 0.0) {
        float firstScore = firstPowerRatio * firstImportance;
        float secondScore = secondPowerRatio * secondImportance;
        float sum = firstScore + secondScore;
        return sum > 0.0 ? firstScore / sum : -1.0;
    }
    float firstScore = firstPowerRatio * firstImportance
            * (secondDistanceSquared / distanceScale);
    float secondScore = secondPowerRatio * secondImportance
            * (firstDistanceSquared / distanceScale);
    float sum = firstScore + secondScore;
    return sum > 0.0 ? firstScore / sum : -1.0;
}

#endif
