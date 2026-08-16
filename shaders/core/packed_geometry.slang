#language slang 2026
module "core/packed_geometry.slang";

import glsl;
import "prime_abi_types.slang";

public static const uint PRIME_HIT_KIND_MASK = 0xffu;
public static const uint PRIME_SURFACE_MOTION_FLAG = 0x100u;

public uint primeTriangleCount(uint primitiveCount, uint macroTriangleBase)
{
    return macroTriangleBase
            + 2u * (primitiveCount - macroTriangleBase);
}

public uint primeGlobalTriangleIndexValue(
        uint geometryIndex,
        uint primitiveIndex,
        uint opaquePrimitiveCount,
        uint cutoutPrimitiveCount,
        uint opaqueMacroTriangleBase,
        uint cutoutMacroTriangleBase)
{
    uint base = geometryIndex == 0u
            ? 0u
            : primeTriangleCount(
                    opaquePrimitiveCount, opaqueMacroTriangleBase);
    if (geometryIndex == 2u) {
        base += primeTriangleCount(
                cutoutPrimitiveCount, cutoutMacroTriangleBase);
    }
    return base + primitiveIndex;
}

public float2 primeUnpackUv(uint packedValue)
{
    uint2 fixedUv = uint2(packedValue & 0xffffu, packedValue >> 16u);
    float2 uv = float2(fixedUv) * (1.0 / 65536.0);
    return float2(
            fixedUv.x == 0xffffu ? 1.0 : uv.x,
            fixedUv.y == 0xffffu ? 1.0 : uv.y);
}

public float4 primeUnpackTint(uint packedValue)
{
    return unpackUnorm4x8ToFloat(packedValue);
}

public bool primeSurfaceHasMotion(SurfaceInteraction surface)
{
    return (surface.motionZFlags & PRIME_SURFACE_MOTION_FLAG) != 0u;
}

public uint primeSurfaceEmitterIndex(SurfaceInteraction surface)
{
    // Dynamic geometry cannot be a light-tree emitter. Its emitter lane carries the triangle id
    // needed to reconstruct the previous surface instead.
    return primeSurfaceHasMotion(surface) ? 0xffffffffu : surface.emitterIndex;
}

public struct PrimePreviousDynamicSurface
{
    public float3 position;
    public float3 geometricNormal;
};

public PrimePreviousDynamicSurface primePreviousDynamicSurfaceValue(
        float3 first,
        float3 second,
        float3 third,
        float3 translation,
        float2 barycentrics)
{
    float3 barycentric = float3(
            1.0 - barycentrics.x - barycentrics.y,
            barycentrics.x,
            barycentrics.y);
    PrimePreviousDynamicSurface previous;
    previous.position = first * barycentric.x
            + second * barycentric.y
            + third * barycentric.z
            + translation;
    float3 normal = cross(second - first, third - first);
    float normalLengthSquared = dot(normal, normal);
    previous.geometricNormal = normalLengthSquared > 1.0e-20
            ? normal * rsqrt(normalLengthSquared)
            : float3(0.0, 1.0, 0.0);
    return previous;
}

public PrimePreviousDynamicSurface primePreviousDynamicSurface(
        SectionRecord section, uint triangleIndex, float2 barycentrics)
{
    float* positions = (float*)section.lightAddress;
    uint vertexBase = triangleIndex * 9u;
    float3 first = float3(
            positions[vertexBase],
            positions[vertexBase + 1u],
            positions[vertexBase + 2u]);
    float3 second = float3(
            positions[vertexBase + 3u],
            positions[vertexBase + 4u],
            positions[vertexBase + 5u]);
    float3 third = float3(
            positions[vertexBase + 6u],
            positions[vertexBase + 7u],
            positions[vertexBase + 8u]);
    return primePreviousDynamicSurfaceValue(
            first, second, third, section.translation, barycentrics);
}

public PrimePreviousDynamicSurface primeSurfacePreviousDynamicSurface(
        SurfaceInteraction surface, uint64_t sectionTableAddress)
{
    SectionRecord section = ((SectionRecord*)sectionTableAddress)[
            surface.sectionIndex];
    return primePreviousDynamicSurface(
            section,
            surface.emitterIndex,
            unpackUnorm2x16ToFloat(asuint(surface.textureLod)));
}

public PrimePreviousDynamicSurface primeSurfacePrevious(
        SurfaceInteraction surface, uint64_t sectionTableAddress)
{
    if (primeSurfaceHasMotion(surface)) {
        return primeSurfacePreviousDynamicSurface(surface, sectionTableAddress);
    }
    PrimePreviousDynamicSurface previous;
    previous.position = surface.position;
    previous.geometricNormal = surface.geometricNormal;
    return previous;
}

public float3 primeSurfacePreviousPosition(
        SurfaceInteraction surface, uint64_t sectionTableAddress)
{
    return primeSurfacePrevious(surface, sectionTableAddress).position;
}

float2 primeSignNotZero(float2 value)
{
    return float2(value.x >= 0.0 ? 1.0 : -1.0, value.y >= 0.0 ? 1.0 : -1.0);
}

public float3 primeUnpackOctahedralNormal(uint packedValue)
{
    float2 encoded = unpackSnorm2x16ToFloat(packedValue);
    float3 normal = float3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * primeSignNotZero(normal.xy);
    }
    return normalize(normal);
}

public uint primePackOctahedralNormal(float3 value)
{
    float3 normal = normalize(value);
    normal /= max(abs(normal.x) + abs(normal.y) + abs(normal.z), 1.0e-20);
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * primeSignNotZero(normal.xy);
    }
    return packSnorm2x16(clamp(normal.xy, float2(-1.0), float2(1.0)));
}
