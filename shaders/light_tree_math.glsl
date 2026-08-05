#ifndef PRIME_LIGHT_TREE_MATH_GLSL
#define PRIME_LIGHT_TREE_MATH_GLSL

const uint PRIME_LIGHT_RECEIVER_NORMAL_ACTIVE = 0x8000u;
const float PRIME_LIGHT_RECEIVER_COSINE_FLOOR = 1.0 / 256.0;
const uint PRIME_LIGHT_DIRECTION_MODE_SHIFT = 30u;
const uint PRIME_LIGHT_DIRECTION_MODE_LOBES = 2u;
const uint PRIME_LIGHT_DIRECTION_MODE_FULL = 3u;
const uint PRIME_LIGHT_DIRECTION_OCT_MASK = 0x3ffu;
const uint PRIME_LIGHT_DIRECTION_LOBE_MASK = 0x1fu;

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

vec3 primeUnpackLightEmissionAxis(uint packedValue) {
    vec2 encoded = vec2(
            float(packedValue & PRIME_LIGHT_DIRECTION_OCT_MASK),
            float((packedValue >> 10u) & PRIME_LIGHT_DIRECTION_OCT_MASK))
            / 1023.0 * 2.0 - 1.0;
    vec3 axis = vec3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
    if (axis.z < 0.0) {
        axis.xy = (1.0 - abs(axis.yx)) * primeLightTreeSignNotZero(axis.xy);
    }
    return normalize(axis);
}

float primeLightEmissionAxisCosineBound(
        vec3 boundsMin,
        vec3 boundsMax,
        vec3 point,
        vec3 axis,
        float distanceSquared,
        float inverseDistance) {
    if (!(distanceSquared > 0.0)) {
        return 1.0;
    }
    vec3 support = mix(boundsMax, boundsMin, greaterThanEqual(axis, vec3(0.0)));
    float maximumProjection = dot(axis, point - support);
    if (!(maximumProjection > 0.0)) {
        return 0.0;
    }
    return distanceSquared > maximumProjection * maximumProjection
            ? maximumProjection * inverseDistance
            : 1.0;
}

float primeLightExpandedEmissionConeBound(
        float axisCosineBound, float sineHalfAngle, float cosineHalfAngle) {
    if (axisCosineBound >= cosineHalfAngle) {
        return 1.0;
    }
    float sine = sqrt(max(1.0 - axisCosineBound * axisCosineBound, 0.0));
    return clamp(
            axisCosineBound * cosineHalfAngle + sine * sineHalfAngle,
            0.0,
            1.0);
}

float primeLightEmissionLobe(uint packedValue, uint shift) {
    return float((packedValue >> shift) & PRIME_LIGHT_DIRECTION_LOBE_MASK) / 31.0;
}

float primeLightEmitterCosineBound(
        vec3 boundsMin,
        vec3 boundsMax,
        vec3 point,
        float distanceSquared,
        uint packedDirection) {
    uint mode = packedDirection >> PRIME_LIGHT_DIRECTION_MODE_SHIFT;
    if (mode == PRIME_LIGHT_DIRECTION_MODE_FULL) {
        return 1.0;
    }
    float inverseDistance = distanceSquared > 0.0 ? inversesqrt(distanceSquared) : 0.0;
    if (mode == PRIME_LIGHT_DIRECTION_MODE_LOBES) {
        if (!(distanceSquared > 0.0)) {
            return 1.0;
        }
        // Axis-aligned support shares one inverse distance; mixed nodes need no per-lobe SFU work.
        vec3 positive = min(max(point - boundsMin, vec3(0.0)) * inverseDistance, vec3(1.0));
        vec3 negative = min(max(boundsMax - point, vec3(0.0)) * inverseDistance, vec3(1.0));
        float result = primeLightEmissionLobe(packedDirection, 0u) * positive.x
                + primeLightEmissionLobe(packedDirection, 5u) * negative.x
                + primeLightEmissionLobe(packedDirection, 10u) * positive.y
                + primeLightEmissionLobe(packedDirection, 15u) * negative.y
                + primeLightEmissionLobe(packedDirection, 20u) * positive.z
                + primeLightEmissionLobe(packedDirection, 25u) * negative.z;
        return min(result, 1.0);
    }
    vec3 axis = primeUnpackLightEmissionAxis(packedDirection);
    float sineHalfAngle = float((packedDirection >> 20u) & 0x3ffu) / 1023.0;
    float cosineHalfAngle = sqrt(max(1.0 - sineHalfAngle * sineHalfAngle, 0.0));
    float forward = primeLightExpandedEmissionConeBound(
            primeLightEmissionAxisCosineBound(
                    boundsMin, boundsMax, point, axis, distanceSquared, inverseDistance),
            sineHalfAngle,
            cosineHalfAngle);
    if (mode == 0u) {
        return forward;
    }
    float backward = primeLightExpandedEmissionConeBound(
            primeLightEmissionAxisCosineBound(
                    boundsMin, boundsMax, point, -axis, distanceSquared, inverseDistance),
            sineHalfAngle,
            cosineHalfAngle);
    // Two-sided power integrates both hemispheres, so either directional lobe owns half of it.
    return 0.5 * max(forward, backward);
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
        return 0.0;
    }
    float bound = distanceSquared > maximumProjection * maximumProjection
            ? maximumProjection * inversesqrt(distanceSquared)
            : 1.0;
    return bound;
}

vec3 primeLightNodeMetrics(
        vec4 boundsMinPower,
        vec4 boundsMaxSoftening,
        vec3 point,
        vec3 receiverNormal,
        uint packedEmissionDirection) {
    vec3 closest = clamp(point, boundsMinPower.xyz, boundsMaxSoftening.xyz);
    vec3 delta = point - closest;
    float distanceSquared = dot(delta, delta);
    float receiverBound = primeLightReceiverCosineBound(
            boundsMinPower.xyz,
            boundsMaxSoftening.xyz,
            point,
            receiverNormal,
            distanceSquared);
    float emitterBound = primeLightEmitterCosineBound(
            boundsMinPower.xyz,
            boundsMaxSoftening.xyz,
            point,
            distanceSquared,
            packedEmissionDirection);
    return vec3(
            distanceSquared + boundsMaxSoftening.w,
            boundsMinPower.w,
            // One shared floor preserves support without compounding receiver and emitter floors.
            max(receiverBound * emitterBound, PRIME_LIGHT_RECEIVER_COSINE_FLOOR));
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
