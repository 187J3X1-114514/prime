#language slang 2026
module "math/direct_sampling.slang";

public static const uint PRIME_DIRECT_SAMPLE_AREA_PRIMARY = 0u;
public static const uint PRIME_DIRECT_SAMPLE_AREA_SECONDARY = 1u;
public static const uint PRIME_DIRECT_SAMPLE_SUN = 2u;

static const uint PRIME_DIRECT_SAMPLE_STREAM_COUNT = 3u;
static const uint PRIME_ZSOBOL_LOG2_SAMPLES_PER_PIXEL = 16u;
static const uint PRIME_ZSOBOL_LOG4_SAMPLES_PER_PIXEL = 8u;
static const uint PRIME_ZSOBOL_MATRIX_SIZE = 52u;
static const float PRIME_ZSOBOL_UINT_SCALE = 1.0 / 4294967296.0;
static const float PRIME_ZSOBOL_ONE_MINUS_EPSILON = 0.9999999403953552;

// First two pbrt-v4 Sobol dimensions only. Dimension zero is bit reversal; these are
// dimension one's 52 Joe-Kuo generator columns in the pinned pbrt-v4 revision.
static const uint PRIME_ZSOBOL_DIMENSION_ONE[PRIME_ZSOBOL_MATRIX_SIZE] = {
    0x80000000, 0xc0000000, 0xa0000000, 0xf0000000,
    0x88000000, 0xcc000000, 0xaa000000, 0xff000000,
    0x80800000, 0xc0c00000, 0xa0a00000, 0xf0f00000,
    0x88880000, 0xcccc0000, 0xaaaa0000, 0xffff0000,
    0x80008000, 0xc000c000, 0xa000a000, 0xf000f000,
    0x88008800, 0xcc00cc00, 0xaa00aa00, 0xff00ff00,
    0x80808080, 0xc0c0c0c0, 0xa0a0a0a0, 0xf0f0f0f0,
    0x88888888, 0xcccccccc, 0xaaaaaaaa, 0xffffffff,
    0x80000000, 0xc0000000, 0xa0000000, 0xf0000000,
    0x88000000, 0xcc000000, 0xaa000000, 0xff000000,
    0x80800000, 0xc0c00000, 0xa0a00000, 0xf0f00000,
    0x88880000, 0xcccc0000, 0xaaaa0000, 0xffff0000,
    0x80008000, 0xc000c000, 0xa000a000, 0xf000f000
};

// The complete lexicographic set used by pbrt-v4's ZSobolSampler.
static const uint PRIME_ZSOBOL_PERMUTATIONS[96] = {
    0, 1, 2, 3,  0, 1, 3, 2,  0, 2, 1, 3,  0, 2, 3, 1,
    0, 3, 2, 1,  0, 3, 1, 2,  1, 0, 2, 3,  1, 0, 3, 2,
    1, 2, 0, 3,  1, 2, 3, 0,  1, 3, 2, 0,  1, 3, 0, 2,
    2, 1, 0, 3,  2, 1, 3, 0,  2, 0, 1, 3,  2, 0, 3, 1,
    2, 3, 0, 1,  2, 3, 1, 0,  3, 1, 2, 0,  3, 1, 0, 2,
    3, 2, 1, 0,  3, 2, 0, 1,  3, 0, 2, 1,  3, 0, 1, 2
};

public struct PrimeDirectSampleBase
{
    public uint64_t mortonIndex;
    public uint dimensionBase;
    public uint digitCount;
    public uint seed;
};

uint64_t primeZSobolConstant(uint high, uint low)
{
    return (uint64_t(high) << 32u) | uint64_t(low);
}

// Exact pbrt-v4 MixBits permutation selector, specialized here to keep the direct
// entry-point closure independent of the generic renderer sampling implementation.
uint64_t primeZSobolMixBits(uint64_t value)
{
    value ^= value >> 31u;
    value *= primeZSobolConstant(0x7fb5d329, 0x728ea185);
    value ^= value >> 27u;
    value *= primeZSobolConstant(0x81dadef4, 0xbc2dd44d);
    value ^= value >> 33u;
    return value;
}

uint64_t primeZSobolLeftShift2(uint value)
{
    uint64_t expanded = uint64_t(value);
    expanded = (expanded ^ (expanded << 16u))
            & primeZSobolConstant(0x0000ffff, 0x0000ffff);
    expanded = (expanded ^ (expanded << 8u))
            & primeZSobolConstant(0x00ff00ff, 0x00ff00ff);
    expanded = (expanded ^ (expanded << 4u))
            & primeZSobolConstant(0x0f0f0f0f, 0x0f0f0f0f);
    expanded = (expanded ^ (expanded << 2u))
            & primeZSobolConstant(0x33333333, 0x33333333);
    expanded = (expanded ^ (expanded << 1u))
            & primeZSobolConstant(0x55555555, 0x55555555);
    return expanded;
}

uint64_t primeZSobolMorton(uint2 pixel)
{
    return (primeZSobolLeftShift2(pixel.y) << 1u)
            | primeZSobolLeftShift2(pixel.x);
}

uint primeZSobolResolutionDigits(uint2 extent)
{
    uint remaining = max(extent.x, extent.y) - 1u;
    uint digits = 0u;
    while (remaining != 0u) {
        ++digits;
        remaining >>= 1u;
    }
    return digits;
}

public PrimeDirectSampleBase primeMakeDirectSampleBase(
        uint2 pixel,
        uint2 outputExtent,
        uint sampleIndex,
        uint sampleEpoch,
        uint vertexIndex,
        uint pathBranch)
{
    PrimeDirectSampleBase result;
    result.mortonIndex = (primeZSobolMorton(pixel)
                    << PRIME_ZSOBOL_LOG2_SAMPLES_PER_PIXEL)
            | uint64_t(sampleIndex);
    uint pathPair = vertexIndex * 2u + pathBranch;
    result.dimensionBase = pathPair
            * PRIME_DIRECT_SAMPLE_STREAM_COUNT * 2u;
    result.digitCount = primeZSobolResolutionDigits(outputExtent)
            + PRIME_ZSOBOL_LOG4_SAMPLES_PER_PIXEL;
    result.seed = sampleEpoch;
    return result;
}

uint64_t primeZSobolSampleIndex(PrimeDirectSampleBase base, uint dimension)
{
    uint64_t sampleIndex = uint64_t(0);
    uint digitIndex = base.digitCount;
    while (digitIndex != 0u) {
        --digitIndex;
        uint digitShift = 2u * digitIndex;
        uint digit = uint((base.mortonIndex >> digitShift) & uint64_t(3));
        uint64_t higherDigits = base.mortonIndex >> (digitShift + 2u);
        uint dimensionHash = 0x55555555u * dimension;
        uint permutation = uint(
                (primeZSobolMixBits(higherDigits ^ uint64_t(dimensionHash))
                        >> 24u) % uint64_t(24));
        digit = PRIME_ZSOBOL_PERMUTATIONS[permutation * 4u + digit];
        sampleIndex |= uint64_t(digit) << digitShift;
    }
    return sampleIndex;
}

uint primeZSobolDimensionOne(uint64_t sampleIndex)
{
    uint value = 0u;
    uint column = 0u;
    while (sampleIndex != uint64_t(0)) {
        if ((sampleIndex & uint64_t(1)) != uint64_t(0)) {
            value ^= PRIME_ZSOBOL_DIMENSION_ONE[column];
        }
        sampleIndex >>= 1u;
        ++column;
    }
    return value;
}

uint primeZSobolFastOwen(uint value, uint seed)
{
    value = reversebits(value);
    value ^= value * 0x3d20adea;
    value += seed;
    value *= (seed >> 16u) | 1u;
    value ^= value * 0x05526c56;
    value ^= value * 0x53a22864;
    return reversebits(value);
}

// pbrt-v4 Hash(dimension, seed), specialized for its two adjacent 32-bit arguments.
uint64_t primeZSobolSampleHash(uint dimension, uint seed)
{
    uint64_t multiplier = primeZSobolConstant(0xc6a4a793, 0x5bd1e995);
    uint64_t hash = uint64_t(8) * multiplier;
    uint64_t key = uint64_t(dimension) | (uint64_t(seed) << 32u);
    key *= multiplier;
    key ^= key >> 47u;
    key *= multiplier;
    hash ^= key;
    hash *= multiplier;
    hash ^= hash >> 47u;
    hash *= multiplier;
    hash ^= hash >> 47u;
    return hash;
}

float primeZSobolFloat(uint value)
{
    return min(
            float(value) * PRIME_ZSOBOL_UINT_SCALE,
            PRIME_ZSOBOL_ONE_MINUS_EPSILON);
}

public float2 primeDirectSample2D(
        PrimeDirectSampleBase base, uint stream)
{
    uint dimension = base.dimensionBase + stream * 2u;
    uint64_t sampleIndex = primeZSobolSampleIndex(base, dimension);
    uint64_t hash = primeZSobolSampleHash(dimension + 2u, base.seed);
    uint first = primeZSobolFastOwen(
            reversebits(uint(sampleIndex)), uint(hash));
    uint second = primeZSobolFastOwen(
            primeZSobolDimensionOne(sampleIndex), uint(hash >> 32u));
    return float2(primeZSobolFloat(first), primeZSobolFloat(second));
}
