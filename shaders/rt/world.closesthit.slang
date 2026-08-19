import glsl;
import "prime_abi_types.slang";
import "core/packed_geometry.slang";
import "core/render_config.slang";
import "integrator/path_contract.slang";
import "material/default_material.slang";
import "material/material.slang";

import "rt/hit_common.slang";

layout(location = 0) rayPayloadInEXT TracePayload primePayload;

uint primeTriangleDebugHash(uint value) {
    value ^= value >> 16u;
    value *= 0x21f0aaadu;
    value ^= value >> 15u;
    value *= 0xf35a2d97u;
    return value ^ (value >> 15u);
}

float3 primeTriangleDebugColor(PrimitiveRecord primitive) {
    // TLAS instance indices are allocation-order state and change whenever streamed clusters
    // rebuild the table. Primitive contents and their deterministic BLAS order remain stable.
    uint seed = primeTriangleDebugHash(primitive.uv0 ^ primitive.uv1 ^ primitive.uv2);
    seed = primeTriangleDebugHash(seed ^ primitive.tint);
    seed = primeTriangleDebugHash(seed ^ primitive.normal);
    seed = primeTriangleDebugHash(seed ^ primitive.flagsEmitter);
    seed = primeTriangleDebugHash(seed ^ primitive.uvDensity ^ primitive.tangent);
    seed = primeTriangleDebugHash(seed ^ GeometryIndex());
    seed = primeTriangleDebugHash(seed ^ PrimitiveIndex());
    float hue = float(seed & 0xffffu) * (1.0 / 65536.0);
    float3 rgb = clamp(
            abs(frac(hue + float3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0,
            0.0,
            1.0);
    return 0.9 * lerp(float3(1.0), rgb, 0.75);
}

[shader("closesthit")]
void main(
        in BuiltInTriangleIntersectionAttributes attributes) {
    float2 barycentrics = attributes.barycentrics;
    SectionRecord section = primeSection();
    PrimitiveRecord primitive = primePrimitive(section);
    PrimeSurfaceRelation relation = primeSurfaceRelation(section);
    float3 authoredNormal = primeUnpackOctahedralNormal(primitive.normal);
    PrimeHitTriangle hit = primeHitTriangle(
            section, barycentrics, authoredNormal);
    float3 normal = hit.geometricNormal;
    bool dynamic = (primitive.flagsEmitter & PRIME_DYNAMIC_TEXTURE_FLAG) != 0u
            && section.lightAddress != uint64_t(0);
    primePayload.hitKind = PRIME_HIT_SURFACE;
    primitive = primeResolveSurfacePrimitive(
            section,
            primitive,
            relation,
            barycentrics,
            hit.localPosition,
            normal);
    bool bakedMaterial = primeUsesBakedMaterial(primitive);
    float textureLodValue =
            bakedMaterial
                    ? 0.0
                    : primeRayConeTextureLod(primitive, normal, hit.position);
    float2 materialUv = bakedMaterial
            ? float2(0.0)
            : primeInterpolateUv(
                    section, primitive, barycentrics, hit.localPosition, normal);
    PrimeMaterialSample material = primeEvaluateMaterial(
            primitive, materialUv, textureLodValue, section.instanceTint);
    primePayload.t = RayTCurrent();
    // Keep the authored outward normal. Opaque shading orients it at the integrator boundary,
    // while transparent reflection and the water stack require its authored outward sign.
    primePayload.geometricNormal = normal;
    primePayload.position = hit.position;
    primePayload.positionReserved = primePackSurfaceMotion(
            hit.triangleIndex, dynamic);
    primePayload.baseColor = primeVisualizesTriangles()
            ? lerp(material.baseColor, primeTriangleDebugColor(primitive), 0.5)
            : material.baseColor;
    primePayload.materialControl = material.materialControl;
    primePayload.sectionIndex = InstanceID();
    if (dynamic) {
        // Dynamic geometry cannot emit. Preserve both hardware barycentrics as f32 in its otherwise
        // unused emitter/LOD lanes so current and previous positions use the same interpolation.
        primePayload.emitterIndex = asuint(barycentrics.x);
        primePayload.textureLod = barycentrics.y;
    } else {
        primePayload.emitterIndex = primePrimitiveEmitterIndex(primitive);
        primePayload.textureLod = textureLodValue;
    }
    primePayload.opacity = material.opacity;
    primePayload.shadingNormal = primePackOctahedralNormal(material.shadingNormal);
    primePayload.roughness = material.roughness;
    primePayload.opticalControl = material.opticalControl;
    primePayload.adjacentBaseColor = float3(0.0);
    primePayload.adjacentInterfaceControl = 0u;
    if ((relation.control & PRIME_SURFACE_RELATION_KIND_MASK)
            == PRIME_SURFACE_RELATION_BOUNDARY) {
        PrimeMaterialSample adjacent = primeEvaluateMaterialReference(
                relation.control >> 8u,
                relation.tint,
                primeUnpackUv(relation.referenceUv));
        primePayload.adjacentBaseColor = adjacent.baseColor;
        uint adjacentFresnelCode = adjacent.opticalControl
                & PRIME_OPTICAL_FRESNEL_MASK;
        primePayload.adjacentInterfaceControl =
                (adjacent.materialControl & 0xffu)
                | adjacentFresnelCode << 8u
                | 1u << 16u
                | ((relation.control & PRIME_SURFACE_RELATION_MICRO_GAP_ELIGIBLE) != 0u
                        ? 1u << 17u
                        : 0u);
    }
}
