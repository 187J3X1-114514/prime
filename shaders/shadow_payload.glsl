#ifndef PRIME_SHADOW_PAYLOAD_GLSL
#define PRIME_SHADOW_PAYLOAD_GLSL

struct PrimeShadowPayload {
    vec3 opticalDepth;
    float hitDistance;
    float rayDistance;
};

#endif
