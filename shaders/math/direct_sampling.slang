#language slang 2026
module "math/direct_sampling.slang";

public static const uint PRIME_DIRECT_SAMPLE_AREA_PRIMARY = 0u;
public static const uint PRIME_DIRECT_SAMPLE_AREA_SECONDARY = 1u;
public static const uint PRIME_DIRECT_SAMPLE_SUN = 2u;

static const uint PRIME_DIRECT_SAMPLE_STREAM_COUNT = 3u;
static const uint PRIME_ZSOBOL_LOG2_SAMPLES_PER_PIXEL = 16u;
static const uint PRIME_ZSOBOL_LOG4_SAMPLES_PER_PIXEL = 8u;
static const float PRIME_ZSOBOL_UINT_SCALE = 1.0 / 4294967296.0;
static const float PRIME_ZSOBOL_ONE_MINUS_EPSILON = 0.9999999403953552;

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
    return remaining == 0u ? 0u : uint(firstbithigh(remaining)) + 1u;
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

// Exact remainder without the out-of-line NVIDIA u64 division helper. 2^32 is 16
// modulo 24, and the high limb only needs its residue modulo 3.
uint primeZSobolModulo24(uint64_t value)
{
    uint remainder = uint(value) % 24u
            + 16u * (uint(value >> 32u) % 3u);
    remainder -= remainder >= 24u ? 24u : 0u;
    remainder -= remainder >= 24u ? 24u : 0u;
    return remainder;
}

// Four two-bit outputs are packed in each byte. The byte order is the exact pbrt-v4
// table order; it is not the conventional factoradic permutation order.
uint primeZSobolPermutation(uint permutation, uint digit)
{
    uint word = 0x78d8b4e4u;
    uint wordIndex = permutation >> 2u;
    word = wordIndex == 1u ? 0xb1e19c6cu : word;
    word = wordIndex == 2u ? 0x8d2d39c9u : word;
    word = wordIndex == 3u ? 0x72d236c6u : word;
    word = wordIndex == 4u ? 0x87271e4eu : word;
    word = wordIndex == 5u ? 0x93634b1bu : word;
    uint packed = word >> ((permutation & 3u) * 8u);
    return (packed >> (digit * 2u)) & 3u;
}

uint64_t primeZSobolSampleIndex(PrimeDirectSampleBase base, uint dimension)
{
    uint64_t source = base.mortonIndex
            << (64u - 2u * base.digitCount);
    uint64_t higherDigits = uint64_t(0);
    uint64_t sampleIndex = uint64_t(0);
    uint remainingDigits = base.digitCount;
    uint dimensionHash = 0x55555555u * dimension;
    while (remainingDigits != 0u) {
        uint digit = uint(source >> 62u);
        uint permutation = primeZSobolModulo24(
                primeZSobolMixBits(
                        higherDigits ^ uint64_t(dimensionHash)) >> 24u);
        sampleIndex = (sampleIndex << 2u)
                | uint64_t(primeZSobolPermutation(permutation, digit));
        higherDigits = (higherDigits << 2u) | uint64_t(digit);
        source <<= 2u;
        --remainingDigits;
    }
    return sampleIndex;
}

// The pinned dimension-one columns are reversebits((1 + x)^i). Folding the limbs
// accounts for their 32-column period. Returning the reversed-domain value also
// cancels FastOwen's immediately following input reversal.
uint primeZSobolDimensionOneReversed(uint64_t sampleIndex)
{
    uint value = uint(sampleIndex) ^ uint(sampleIndex >> 32u);
    value ^= (value & 0xaaaaaaaau) >> 1u;
    value ^= (value & 0xccccccccu) >> 2u;
    value ^= (value & 0xf0f0f0f0u) >> 4u;
    value ^= (value & 0xff00ff00u) >> 8u;
    value ^= (value & 0xffff0000u) >> 16u;
    return value;
}

uint primeZSobolFastOwenFromReversed(uint value, uint seed)
{
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
    uint first = primeZSobolFastOwenFromReversed(
            uint(sampleIndex), uint(hash));
    uint second = primeZSobolFastOwenFromReversed(
            primeZSobolDimensionOneReversed(sampleIndex), uint(hash >> 32u));
    return float2(primeZSobolFloat(first), primeZSobolFloat(second));
}
