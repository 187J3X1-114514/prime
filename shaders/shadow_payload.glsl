#ifndef PRIME_SHADOW_PAYLOAD_GLSL
#define PRIME_SHADOW_PAYLOAD_GLSL

struct PrimeShadowPayload {
    // Keep cross-stage payload lanes explicitly four-wide. The final lanes track Prime's two
    // starting stack entries and signed windings so exits reuse the path's exact extinction;
    // a tiny RGB residual would otherwise be magnified by a distant-light endpoint.
    vec4 opticalDepthMomentHitDistance;
    vec4 terminalExtinctionRayDistance;
    vec4 startingExtinction0Winding;
    vec4 startingExtinction1Winding;
    uint startingMediumCount;
};

#endif
