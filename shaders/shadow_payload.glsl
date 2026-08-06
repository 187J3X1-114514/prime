#ifndef PRIME_SHADOW_PAYLOAD_GLSL
#define PRIME_SHADOW_PAYLOAD_GLSL

struct PrimeShadowPayload {
    // Keep cross-stage payload lanes explicitly four-wide. The final integer tracks water as a
    // signed winding number so entering and exiting the ray's starting medium cancel exactly;
    // a tiny RGB residual would otherwise be magnified by a distant-light endpoint.
    vec4 opticalDepthMomentHitDistance;
    vec4 terminalExtinctionRayDistance;
    int waterWinding;
};

#endif
