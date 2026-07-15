#version 460
#extension GL_GOOGLE_include_directive : require
#include "hit_common.glsl"

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

void main() {
    SectionRecord section = primeSection();
    PrimitiveRecord primitive = primePrimitive(section);
    primePayload.hitKind = 1u;
    vec3 normal = primeUnpackOctahedralNormal(primitive.normal);
    float textureLodValue = primeRayConeTextureLod(primitive, normal);
    MaterialEvaluation material = primeEvaluateMaterial(
            primitive, primeInterpolateUv(primitive), textureLodValue);
    primePayload.position = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT;
    primePayload.t = gl_HitTEXT;
    if (dot(normal, -gl_WorldRayDirectionEXT) < 0.0) {
        normal = -normal;
    }
    primePayload.geometricNormal = normal;
    primePayload.baseColor = material.baseColor;
    primePayload.traceKind = material.flags;
    primePayload.sectionIndex = gl_InstanceCustomIndexEXT;
    primePayload.emitterIndex = primitive.reserved0 == 0u
            ? 0xffffffffu
            : primitive.reserved0 - 1u;
    primePayload.reserved0 = floatBitsToUint(textureLodValue);
}
