#ifndef PRIME_FSR_INPUT_GLSL
#define PRIME_FSR_INPUT_GLSL

#include "nrd_common.glsl"

const float PRIME_FSR_NEAR_PLANE = 0.05;

float primeFsrSurfaceViewZ(vec3 position, vec3 cameraForward) {
    float viewZ = dot(position, cameraForward);
    return primeNrdIsFinite(viewZ) && viewZ > 0.0
            ? min(viewZ, PRIME_NRD_FP16_MAX)
            : PRIME_NRD_FP16_MAX;
}

float primeFsrReversedInfiniteDepth(float viewZ) {
    return primeNrdIsFinite(viewZ) && viewZ > 0.0
            ? clamp(PRIME_FSR_NEAR_PLANE / viewZ, 0.0, 1.0)
            : 0.0;
}

vec2 primeFsrNormalizedMotion(vec2 previousUv, vec2 currentUv) {
    vec2 motion = previousUv - currentUv;
    return primeNrdIsFinite(motion)
            ? clamp(motion, vec2(-1.0), vec2(1.0))
            : vec2(0.0);
}

#endif
