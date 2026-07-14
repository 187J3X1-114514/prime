#version 460
#extension GL_GOOGLE_include_directive : require
#include "hit_common.glsl"

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

void main() {
    PrimitiveRecord primitive = primePrimitive();
    primePayload.hitKind = 1u;
    if (primePayload.traceKind == 1u) {
        return;
    }
    MaterialEvaluation material = primeEvaluateMaterial(primitive, primeInterpolateUv(primitive));
    primePayload.position = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT;
    primePayload.t = gl_HitTEXT;
    vec3 normal = primeUnpackOctahedralNormal(primitive.normal);
    if (dot(normal, -gl_WorldRayDirectionEXT) < 0.0) {
        normal = -normal;
    }
    primePayload.geometricNormal = normal;
    primePayload.baseColor = material.baseColor;
    primePayload.traceKind = material.flags;
}
