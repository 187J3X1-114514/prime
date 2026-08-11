
import glsl;
import "prime_abi_types.slang";
import "core/packed_geometry.slang";
import "core/render_config.slang";
import "material/default_material.slang";
import "material/material.slang";
import "material/transparent_material.slang";

import "rt/hit_common.slang";
import "rt/shadow_payload.slang";

layout(location = 1) rayPayloadInEXT PrimeShadowPayload primeShadowPayload;

void primeAccumulateSolidMediumBoundary(
        inout PrimeShadowPayload primeShadowPayload,
        float3 extinction,
        bool entering) {
    int startingMediumIndex = primeShadowStartingMediumIndex(
            extinction,
            primeShadowPayload.startingExtinction0Winding,
            primeShadowPayload.startingExtinction1Winding,
            primeShadowPayload.startingMediumCount,
            entering);
    float3 boundaryExtinction = startingMediumIndex >= 0
            ? primeShadowStartingMediumCoefficient(
                    primeShadowPayload.startingExtinction0Winding.xyz,
                    primeShadowPayload.startingExtinction1Winding.xyz,
                    uint(startingMediumIndex))
            : extinction;
    primeShadowPayload.opticalDepthMomentHitDistance.xyz =
            primeShadowBoundaryOpticalDepthMoment(
                    primeShadowPayload.opticalDepthMomentHitDistance.xyz,
                    boundaryExtinction,
                    RayTCurrent(),
                    entering);
    if (startingMediumIndex == 0) {
        primeShadowPayload.startingExtinction0Winding.w += entering ? 1.0 : -1.0;
    } else if (startingMediumIndex == 1) {
        primeShadowPayload.startingExtinction1Winding.w += entering ? 1.0 : -1.0;
    } else {
        primeShadowPayload.terminalExtinctionRayDistance.xyz =
                primeShadowBoundaryTerminalExtinction(
                        primeShadowPayload.terminalExtinctionRayDistance.xyz,
                        boundaryExtinction,
                        entering);
    }
}

float3 primeSurfaceBoundaryExtinction(PrimeSurfaceRelation relation) {
    if (((relation.control >> 8u) & PRIME_RECIPE_WATER) != 0u) {
        return PRIME_PURE_WATER_ABSORPTION_M_INV;
    }
    float opacity;
    float3 baseColor = primeAtlasBaseColor(
            relation.tint,
            primeUnpackUv(relation.referenceUv),
            0.0,
            opacity);
    return primeGlassShadowExtinction(baseColor, opacity);
}

[shader("anyhit")]
void main(
        in BuiltInTriangleIntersectionAttributes attributes) {
    float2 barycentrics = attributes.barycentrics;
    SectionRecord section = primeSection();
    PrimitiveRecord primitive = primePrimitive(section);
    PrimeSurfaceRelation relation = primeSurfaceRelation(section);
    float3 outwardNormal = primeUnpackOctahedralNormal(primitive.normal);
    float textureLodValue = primeRayConeTextureLod(primitive, outwardNormal);
    primitive = primeResolveSurfacePrimitive(
            section, primitive, relation, textureLodValue, barycentrics);
    textureLodValue = primeRayConeTextureLod(primitive, outwardNormal);
    uint recipeControl = primePrimitiveControl(primitive);
    float2 materialUv = primeInterpolateUv(
            section, primitive, barycentrics);
    float surfaceOpacity = primeEvaluateOpacity(
            primitive, materialUv, textureLodValue);
    if ((recipeControl & PRIME_RECIPE_ALPHA_CUTOUT) != 0u
            && surfaceOpacity < PRIME_CUTOUT_ALPHA_THRESHOLD) {
        IgnoreHit();
        return;
    }
    if (!primeRecipeIsTransmissive(recipeControl)) {
        return;
    }
    float3 extinction;
    if ((recipeControl & PRIME_RECIPE_WATER) != 0u) {
        extinction = PRIME_PURE_WATER_ABSORPTION_M_INV;
    } else {
        float opacity;
        float3 baseColor = primeAtlasBaseColor(
                primitive.tint,
                primePrimitiveMaterialReferenceUv(primitive),
                0.0,
                opacity);
        if (!primeGlassReferenceIsStained(opacity)
                && primeGlassTexelIsRough(
                        surfaceOpacity, primeUsesSeamlessGlass())) {
            // Clear-glass decoration is an opaque diffuse border, not part of the dielectric
            // volume. Accepting the candidate lets the ordinary closest-hit record occlusion.
            return;
        }
        extinction = primeGlassShadowExtinction(baseColor, opacity);
    }
    extinction = primeShadowCanonicalExtinction(extinction);
    float cosine = dot(outwardNormal, WorldRayDirection());
    if (primeRecipeIsThinWalled(recipeControl)) {
        primeShadowPayload.opticalDepthMomentHitDistance.xyz = primeShadowThinOpticalDepth(
                primeShadowPayload.opticalDepthMomentHitDistance.xyz,
                extinction,
                primeShadowPayload.terminalExtinctionRayDistance.w,
                cosine);
    } else {
        bool entering = cosine < 0.0;
        primeAccumulateSolidMediumBoundary(
                primeShadowPayload, extinction, entering);
        if ((relation.control & PRIME_SURFACE_RELATION_KIND_MASK)
                == PRIME_SURFACE_RELATION_BOUNDARY) {
            primeAccumulateSolidMediumBoundary(
                    primeShadowPayload,
                    primeShadowCanonicalExtinction(
                            primeSurfaceBoundaryExtinction(relation)),
                    !entering);
        }
    }
    IgnoreHit();
}
