#language slang 2026
module "math/direct_sampling.slang";

import "prime_abi_types.slang";
import "prime_abi_bindings.slang";
import "math/sampling.slang";

public static const uint PRIME_DIRECT_SAMPLE_AREA_PRIMARY = 0u;
public static const uint PRIME_DIRECT_SAMPLE_AREA_SECONDARY = 1u;
public static const uint PRIME_DIRECT_SAMPLE_SUN = 2u;

static const uint PRIME_DIRECT_SAMPLE_COUNT = 65536u;
static const uint PRIME_DIRECT_SAMPLE_STREAM_COUNT = 3u;
static const uint PRIME_DIRECT_BLUE_NOISE_RESOLUTION = 128u;
static const uint PRIME_DIRECT_BLUE_NOISE_TEXEL_COUNT =
        PRIME_DIRECT_BLUE_NOISE_RESOLUTION * PRIME_DIRECT_BLUE_NOISE_RESOLUTION;
static const uint PRIME_DIRECT_BLUE_NOISE_OFFSET =
        PRIME_DIRECT_SAMPLE_COUNT * PRIME_DIRECT_SAMPLE_STREAM_COUNT;
static const float PRIME_UINT16_TO_FLOAT_SCALE = 1.0 / 65535.0;

public struct PrimeDirectSampleBase
{
    public uint sequenceIndex;
    public uint tileIndex;
};

[[vk::binding(PRIME_DESCRIPTOR_DIRECT_SAMPLE_LUT, 0)]]
StructuredBuffer<uint2> primeDirectSampleLut;

public PrimeDirectSampleBase primeMakeDirectSampleBase(
        uint2 pixel,
        uint sampleIndex,
        uint sampleEpoch,
        uint vertexIndex,
        uint pathBranch)
{
    uint sequenceSeed = primeHashCombine(sampleEpoch, vertexIndex);
    sequenceSeed = primeHashCombine(sequenceSeed, pathBranch);
    PrimeDirectSampleBase result;
    result.sequenceIndex = (sampleIndex ^ primeHash32(sequenceSeed)) & 0xffffu;
    result.tileIndex = (pixel.x & (PRIME_DIRECT_BLUE_NOISE_RESOLUTION - 1u))
                    * PRIME_DIRECT_BLUE_NOISE_RESOLUTION
            + (pixel.y & (PRIME_DIRECT_BLUE_NOISE_RESOLUTION - 1u));
    return result;
}

public float2 primeDirectSample2D(
        PrimeDirectSampleBase base, uint stream)
{
    uint temporalIndex = stream * PRIME_DIRECT_SAMPLE_COUNT
            + base.sequenceIndex;
    uint spatialIndex = PRIME_DIRECT_BLUE_NOISE_OFFSET
            + stream * PRIME_DIRECT_BLUE_NOISE_TEXEL_COUNT
            + base.tileIndex;
    uint2 temporal = primeDirectSampleLut[temporalIndex];
    uint2 rotation = primeDirectSampleLut[spatialIndex];
    return frac(float2(
            primeUintToFloatExclusive(temporal.x),
            primeUintToFloatExclusive(temporal.y))
            + float2(rotation) * PRIME_UINT16_TO_FLOAT_SCALE);
}
