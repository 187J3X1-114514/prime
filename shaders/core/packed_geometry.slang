#language slang 2026
module "core/packed_geometry.slang";

import "prime_abi_types.slang";

public static const uint PRIME_HIT_KIND_MASK = 0xffu;
public static const uint PRIME_SURFACE_MOTION_FLAG = 0x100u;

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
    // Dynamic geometry cannot be a light-tree emitter. Its payload emitter lane therefore carries
    // two FP16 motion components until this semantic accessor restores the no-emitter contract.
    return primeSurfaceHasMotion(surface) ? 0xffffffffu : surface.emitterIndex;
}

public float3 primeSurfacePreviousPosition(SurfaceInteraction surface)
{
    if (!primeSurfaceHasMotion(surface)) {
        return surface.position;
    }
    float2 xy = unpackHalf2x16ToFloat(surface.emitterIndex);
    float z = unpackHalf2x16ToFloat(surface.motionZFlags).y;
    return surface.position + float3(xy, z);
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
