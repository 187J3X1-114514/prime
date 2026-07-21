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
    // Keep the authored outward normal. Opaque shading orients it at the integrator boundary,
    // while transmissive BSDFs require its sign to distinguish entering from exiting a medium.
    primePayload.geometricNormal = normal;
    primePayload.baseColor = material.baseColor;
    primePayload.traceKind = material.flags;
    primePayload.sectionIndex = gl_InstanceCustomIndexEXT;
    uint encodedEmitter = primitive.flagsEmitter >> 1u;
    primePayload.emitterIndex = encodedEmitter == 0u
            ? 0xffffffffu
            : encodedEmitter - 1u;
    primePayload.textureLod = floatBitsToUint(textureLodValue);
    primePayload.opacity = floatBitsToUint(material.opacity);
    primePayload.shadingNormal = primePackOctahedralNormal(material.shadingNormal);
    primePayload.labPbrNormal = material.labPbrNormal;
    primePayload.labPbrSpecular = material.labPbrSpecular;
}
