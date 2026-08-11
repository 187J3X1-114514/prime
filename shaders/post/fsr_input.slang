#ifndef PRIME_FSR_INPUT_GLSL
#define PRIME_FSR_INPUT_GLSL

#include "core/nrd_common.slang"
#include "prime_fsr_contract.slang"

float primeFsrSurfaceViewZ(float3 position, float3 cameraForward) {
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

float2 primeFsrNormalizedMotion(float2 previousUv, float2 currentUv) {
    float2 motion = previousUv - currentUv;
    return primeNrdIsFinite(motion)
            ? clamp(motion, float2(-1.0), float2(1.0))
            : float2(0.0);
}

float primeFsrSanitizeMask(float value) {
    return primeNrdSanitizeUnit(value, 0.0);
}

#endif
