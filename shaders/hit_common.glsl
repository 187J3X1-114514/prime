#ifndef PRIME_HIT_COMMON_GLSL
#define PRIME_HIT_COMMON_GLSL

#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

hitAttributeEXT vec2 primeBarycentrics;

PrimitiveRecord primePrimitive() {
    SectionTable sections = SectionTable(primePush.sectionTableAddress);
    SectionRecord section = sections.sections[gl_InstanceCustomIndexEXT];
    uint base = gl_GeometryIndexEXT == 0 ? section.opaqueBase : section.cutoutBase;
    PrimitiveBuffer primitives = PrimitiveBuffer(section.primitiveAddress);
    return primitives.records[base + gl_PrimitiveID];
}

vec2 primeInterpolateUv(PrimitiveRecord primitive) {
    vec3 barycentric = vec3(1.0 - primeBarycentrics.x - primeBarycentrics.y,
            primeBarycentrics.x, primeBarycentrics.y);
    return primeUnpackHalf2(primitive.uv0) * barycentric.x
            + primeUnpackHalf2(primitive.uv1) * barycentric.y
            + primeUnpackHalf2(primitive.uv2) * barycentric.z;
}

#endif
