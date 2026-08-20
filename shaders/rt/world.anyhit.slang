import glsl;
import "prime_abi_types.slang";
import "core/packed_geometry.slang";
import "material/default_material.slang";
import "material/material.slang";
import "rt/hit_common.slang";

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    // Slang's GLSL front end otherwise diagnoses this input-only aggregate as uninitialized.
    // t is already reset to zero before traversal and closest-hit overwrites it on acceptance.
    primePayload.t = 0.0;
    float2 barycentrics = attributes.barycentrics;
    SectionRecord section = primeSection();
    uint triangleIndex = primeGlobalTriangleIndex(section);
    bool sameSourceSection = InstanceID() == primePayload.sectionIndex;
    if (sameSourceSection
            && (triangleIndex == primePayload.positionReserved
                    || primeGlobalPrimitiveIndex(section)
                            == primeGlobalPrimitiveIndexFromTriangle(
                                    section, primePayload.positionReserved))) {
        IgnoreHit();
        return;
    }
    // The opaque geometry class needs this shader only for exact source-primitive rejection.
    if (GeometryIndex() == 0u) {
        return;
    }
    PrimitiveRecord primitive = primePrimitive(section);
    bool primaryTransmissive =
            primeRecipeIsTransmissive(primePrimitiveControl(primitive));
    // Without a relation, transparent radiance hits need neither triangle reconstruction nor
    // opacity evaluation.
    if (primaryTransmissive && section.surfaceRelationAddress == uint64_t(0)) {
        return;
    }
    PrimeSurfaceRelation relation = primeSurfaceRelation(section);
    if (primaryTransmissive && relation.control == 0u) {
        return;
    }
    float3 authoredNormal = primeUnpackOctahedralNormal(primitive.normal);
    PrimeHitTriangle hit = primeHitTriangle(
            section, barycentrics, authoredNormal);
    float3 normal = hit.geometricNormal;
    primitive = primeResolveSurfacePrimitive(
            section,
            primitive,
            relation,
            barycentrics,
            hit.localPosition,
            normal);
    if (primeRecipeIsTransmissive(primePrimitiveControl(primitive))) {
        // Radiance rays keep transparent geometry as explicit path vertices. Shadow rays use
        // shadow.anyhit.slang instead, where straight glass and water transmission are accumulated.
        return;
    }
    float opacity = primeEvaluateOpacity(
            primitive,
            primeInterpolateUv(
                    section, primitive, barycentrics, hit.localPosition, normal));
    if (opacity < PRIME_CUTOUT_ALPHA_THRESHOLD) {
        IgnoreHit();
    }
}
