#version 460
#extension GL_GOOGLE_include_directive : require
#include "hit_common.glsl"

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

uint primeTriangleDebugHash(uint value) {
    value ^= value >> 16u;
    value *= 0x21f0aaadu;
    value ^= value >> 15u;
    value *= 0xf35a2d97u;
    return value ^ (value >> 15u);
}

vec3 primeTriangleDebugColor(PrimitiveRecord primitive) {
    // TLAS instance indices are allocation-order state and change whenever streamed clusters
    // rebuild the table. Primitive contents and their deterministic BLAS order remain stable.
    uint seed = primeTriangleDebugHash(primitive.uv0 ^ primitive.uv1 ^ primitive.uv2);
    seed = primeTriangleDebugHash(seed ^ primitive.tint);
    seed = primeTriangleDebugHash(seed ^ primitive.normal);
    seed = primeTriangleDebugHash(seed ^ primitive.flagsEmitter);
    seed = primeTriangleDebugHash(seed ^ primitive.uvDensity ^ primitive.tangent);
    seed = primeTriangleDebugHash(seed ^ gl_GeometryIndexEXT);
    seed = primeTriangleDebugHash(seed ^ gl_PrimitiveID);
    float hue = float(seed & 0xffffu) * (1.0 / 65536.0);
    vec3 rgb = clamp(
            abs(fract(hue + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0,
            0.0,
            1.0);
    return 0.9 * mix(vec3(1.0), rgb, 0.75);
}

void main() {
    SectionRecord section = primeSection();
    PrimitiveRecord primitive = primePrimitive(section);
    primePayload.hitKind = 1u;
    vec3 normal = primeUnpackOctahedralNormal(primitive.normal);
    float textureLodValue = primeRayConeTextureLod(primitive, normal);
    MaterialEvaluation material = primeEvaluateMaterial(
            primitive, primeInterpolateUv(section, primitive), textureLodValue);
    primePayload.position = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT;
    primePayload.t = gl_HitTEXT;
    // Keep the authored outward normal. Opaque shading orients it at the integrator boundary,
    // while transmissive BSDFs require its sign to distinguish entering from exiting a medium.
    primePayload.geometricNormal = normal;
    primePayload.baseColor = primeVisualizesTriangles()
            ? mix(material.baseColor, primeTriangleDebugColor(primitive), 0.5)
            : material.baseColor;
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
