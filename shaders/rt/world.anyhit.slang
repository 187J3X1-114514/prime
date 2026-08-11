#include "rt/hit_common.slang"

[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    float2 barycentrics = attributes.barycentrics;
    SectionRecord section = primeSection();
    PrimitiveRecord primitive = primePrimitive(section);
    PrimeSurfaceRelation relation = primeSurfaceRelation(section);
    float3 normal = primeUnpackOctahedralNormal(primitive.normal);
    float textureLodValue = primeRayConeTextureLod(primitive, normal);
    primitive = primeResolveSurfacePrimitive(
            section, primitive, relation, textureLodValue, barycentrics);
    if (primeRecipeIsTransmissive(primePrimitiveControl(primitive))) {
        // Radiance rays keep transparent geometry as explicit path vertices. Shadow rays use
        // shadow.anyhit.slang instead, where straight glass and water transmission are accumulated.
        return;
    }
    textureLodValue = primeRayConeTextureLod(primitive, normal);
    float opacity = primeEvaluateOpacity(
            primitive,
            primeInterpolateUv(section, primitive, barycentrics),
            textureLodValue);
    if (opacity < PRIME_CUTOUT_ALPHA_THRESHOLD) {
        IgnoreHit();
    }
}
