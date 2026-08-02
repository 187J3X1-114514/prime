#ifndef PRIME_LIGHT_TREE_MATH_GLSL
#define PRIME_LIGHT_TREE_MATH_GLSL

vec2 primeLightNodeMetrics(
        vec4 boundsMinPower,
        vec4 boundsMaxSoftening,
        vec3 point) {
    vec3 closest = clamp(point, boundsMinPower.xyz, boundsMaxSoftening.xyz);
    vec3 delta = point - closest;
    return vec2(
            dot(delta, delta) + boundsMaxSoftening.w,
            boundsMinPower.w);
}

float primeLightBranchProbability(vec2 firstMetrics, vec2 secondMetrics) {
    // Keep this order identical for forward selection and reverse MIS reconstruction.
    float firstDistanceSquared = firstMetrics.x;
    float secondDistanceSquared = secondMetrics.x;
    float firstPower = max(firstMetrics.y, 0.0);
    float secondPower = max(secondMetrics.y, 0.0);
    float powerScale = max(firstPower, secondPower);
    if (powerScale == 0.0) return -1.0;
    float firstPowerRatio = firstPower / powerScale;
    float secondPowerRatio = secondPower / powerScale;
    float distanceScale = max(firstDistanceSquared, secondDistanceSquared);
    if (distanceScale == 0.0) {
        return firstPowerRatio / (firstPowerRatio + secondPowerRatio);
    }
    float firstScore = firstPowerRatio * (secondDistanceSquared / distanceScale);
    float secondScore = secondPowerRatio * (firstDistanceSquared / distanceScale);
    float sum = firstScore + secondScore;
    return sum > 0.0 ? firstScore / sum : -1.0;
}

#endif
