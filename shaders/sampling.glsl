#ifndef PRIME_SAMPLING_GLSL
#define PRIME_SAMPLING_GLSL

// Sampling identities are explicit and independent of call order. This is part of the integrator
// contract: a future wavefront scheduler may move effects to separate kernels without changing
// their sample streams.
const uint PRIME_SAMPLE_EFFECT_CAMERA = 0u;
// Effect identity 1 is intentionally retired. Keeping the remaining identities stable preserves
// their sample streams after explicit environment sampling was removed.
const uint PRIME_SAMPLE_EFFECT_DIRECT_SUN = 2u;
const uint PRIME_SAMPLE_EFFECT_SCATTER_BSDF = 3u;
const uint PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE = 4u;
const uint PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT = 5u;
const uint PRIME_SAMPLE_DIMENSION_PRIMARY = 0u;
const uint PRIME_SAMPLE_DIMENSION_SECONDARY = 1u;

const uint PRIME_SOBOL_INDEX_MASK = 0xffff0000u;
const float PRIME_UINT32_TO_FLOAT_EXCLUSIVE_SCALE = 1.0 / 4294967808.0;
const float PRIME_UINT24_TO_FLOAT_SCALE = 1.0 / 16777216.0;

struct PrimeSampleBase {
    uvec2 pixel;
    uint sampleIndex;
    uint sampleEpoch;
    uint vertexIndex;
    uint pathIndex;
};

// Joe-Kuo direction numbers for the first four Sobol dimensions, in reversed-bit order. The
// Burley construction below Owen-scrambles both the sample index and the resulting dimension.
const uint PRIME_SOBOL_BURLEY_TABLE[4][32] = uint[4][32](
    uint[32](
        0x00000001u, 0x00000002u, 0x00000004u, 0x00000008u,
        0x00000010u, 0x00000020u, 0x00000040u, 0x00000080u,
        0x00000100u, 0x00000200u, 0x00000400u, 0x00000800u,
        0x00001000u, 0x00002000u, 0x00004000u, 0x00008000u,
        0x00010000u, 0x00020000u, 0x00040000u, 0x00080000u,
        0x00100000u, 0x00200000u, 0x00400000u, 0x00800000u,
        0x01000000u, 0x02000000u, 0x04000000u, 0x08000000u,
        0x10000000u, 0x20000000u, 0x40000000u, 0x80000000u),
    uint[32](
        0x00000001u, 0x00000003u, 0x00000005u, 0x0000000fu,
        0x00000011u, 0x00000033u, 0x00000055u, 0x000000ffu,
        0x00000101u, 0x00000303u, 0x00000505u, 0x00000f0fu,
        0x00001111u, 0x00003333u, 0x00005555u, 0x0000ffffu,
        0x00010001u, 0x00030003u, 0x00050005u, 0x000f000fu,
        0x00110011u, 0x00330033u, 0x00550055u, 0x00ff00ffu,
        0x01010101u, 0x03030303u, 0x05050505u, 0x0f0f0f0fu,
        0x11111111u, 0x33333333u, 0x55555555u, 0xffffffffu),
    uint[32](
        0x00000001u, 0x00000003u, 0x00000006u, 0x00000009u,
        0x00000017u, 0x0000003au, 0x00000071u, 0x000000a3u,
        0x00000116u, 0x00000339u, 0x00000677u, 0x000009aau,
        0x00001601u, 0x00003903u, 0x00007706u, 0x0000aa09u,
        0x00010117u, 0x0003033au, 0x00060671u, 0x000909a3u,
        0x00171616u, 0x003a3939u, 0x00717777u, 0x00a3aaaau,
        0x01170001u, 0x033a0003u, 0x06710006u, 0x09a30009u,
        0x16160017u, 0x3939003au, 0x77770071u, 0xaaaa00a3u),
    uint[32](
        0x00000001u, 0x00000003u, 0x00000004u, 0x0000000au,
        0x0000001fu, 0x0000002eu, 0x00000045u, 0x000000c9u,
        0x0000011bu, 0x000002a4u, 0x0000079au, 0x00000b67u,
        0x0000101eu, 0x0000302du, 0x00004041u, 0x0000a0c3u,
        0x0001f104u, 0x0002e28au, 0x000457dfu, 0x000c9baeu,
        0x0011a105u, 0x002a7289u, 0x0079e7dbu, 0x00b6dba4u,
        0x0100011au, 0x030002a7u, 0x0400079eu, 0x0a000b6du,
        0x1f001001u, 0x2e003003u, 0x45004004u, 0xc900a00au));

PrimeSampleBase primeMakeSampleBase(PathState path, uint vertexIndex) {
    PrimeSampleBase result;
    result.pixel = path.pixel;
    result.sampleIndex = path.sampleIndex;
    result.sampleEpoch = path.sampleEpoch;
    result.vertexIndex = vertexIndex;
    result.pathIndex = path.sampleDimension;
    return result;
}

uint primeHash32(uint value) {
    value ^= value >> 16u;
    value *= 0x21f0aaadu;
    value ^= value >> 15u;
    value *= 0xf35a2d97u;
    value ^= value >> 15u;
    return value;
}

uint primeHighQualityHash(uint value) {
    value ^= value >> 16u;
    value *= 0x21f0aaadu;
    value ^= value >> 15u;
    value *= 0xd35a2d97u;
    value ^= value >> 15u;
    return value ^ 0xe6fe3bebu;
}

uint primeHashCombine(uint seed, uint value) {
    return seed ^ (primeHash32(value) + 0x9e3779b9u + (seed << 6u) + (seed >> 2u));
}

uint primeSampleBaseSeed(PrimeSampleBase base) {
    uint seed = primeHash32(base.pixel.x);
    seed = primeHashCombine(seed, base.pixel.y);
    seed = primeHashCombine(seed, base.sampleEpoch);
    seed = primeHashCombine(seed, base.pathIndex);
    return primeHashCombine(seed, base.vertexIndex);
}

uint primeEffectSeed(PrimeSampleBase base, uint effect) {
    return primeHashCombine(primeSampleBaseSeed(base), effect);
}

uint primeReversedBitOwen(uint value, uint seed) {
    value ^= value * 0x3d20adeau;
    value += seed;
    value *= (seed >> 16u) | 1u;
    value ^= value * 0x05526c56u;
    value ^= value * 0x53a22864u;
    return value;
}

float primeUintToFloatExclusive(uint value) {
    return float(value) * PRIME_UINT32_TO_FLOAT_EXCLUSIVE_SCALE;
}

float primeHashToFloat(uint value) {
    return float(value >> 8u) * PRIME_UINT24_TO_FLOAT_SCALE;
}

float primeSobolBurley(uint reversedBitIndex, uint dimension, uint seed) {
    uint result = 0u;
    if (dimension == 0u) {
        result = bitfieldReverse(reversedBitIndex);
    } else {
        uint index = reversedBitIndex;
        uint tableIndex = 0u;
        while (index != 0u) {
            uint leadingZeroes = uint(31 - findMSB(index));
            result ^= PRIME_SOBOL_BURLEY_TABLE[dimension][tableIndex + leadingZeroes];
            tableIndex += leadingZeroes + 1u;
            index <<= leadingZeroes;
            index <<= 1u;
        }
    }
    return primeUintToFloatExclusive(
            bitfieldReverse(primeReversedBitOwen(result, seed)));
}

float primeSobolSample1D(
        PrimeSampleBase base, uint effect, uint dimension) {
    uint mixedSeed = primeEffectSeed(base, effect) ^ primeHighQualityHash(dimension);
    uint shuffledIndex = primeReversedBitOwen(
            bitfieldReverse(base.sampleIndex), mixedSeed ^ 0xbff95bfeu)
            & PRIME_SOBOL_INDEX_MASK;
    return primeSobolBurley(shuffledIndex, 0u, mixedSeed ^ 0x635c77bdu);
}

vec2 primeSobolSample2D(
        PrimeSampleBase base, uint effect, uint dimensionSet) {
    uint mixedSeed = primeEffectSeed(base, effect) ^ primeHighQualityHash(dimensionSet);
    uint shuffledIndex = primeReversedBitOwen(
            bitfieldReverse(base.sampleIndex), mixedSeed ^ 0xf8ade99au)
            & PRIME_SOBOL_INDEX_MASK;
    return vec2(
            primeSobolBurley(shuffledIndex, 0u, mixedSeed ^ 0xe0aaaf76u),
            primeSobolBurley(shuffledIndex, 1u, mixedSeed ^ 0x94964d4eu));
}

vec3 primeSobolSample3D(
        PrimeSampleBase base, uint effect, uint dimensionSet) {
    uint mixedSeed = primeEffectSeed(base, effect) ^ primeHighQualityHash(dimensionSet);
    uint shuffledIndex = primeReversedBitOwen(
            bitfieldReverse(base.sampleIndex), mixedSeed ^ 0xcaa726acu)
            & PRIME_SOBOL_INDEX_MASK;
    return vec3(
            primeSobolBurley(shuffledIndex, 0u, mixedSeed ^ 0x9e78e391u),
            primeSobolBurley(shuffledIndex, 1u, mixedSeed ^ 0x67c33241u),
            primeSobolBurley(shuffledIndex, 2u, mixedSeed ^ 0x78c395c5u));
}

vec4 primeSobolSample4D(
        PrimeSampleBase base, uint effect, uint dimensionSet) {
    uint mixedSeed = primeEffectSeed(base, effect) ^ primeHighQualityHash(dimensionSet);
    uint shuffledIndex = primeReversedBitOwen(
            bitfieldReverse(base.sampleIndex), mixedSeed ^ 0xc2c1a055u)
            & PRIME_SOBOL_INDEX_MASK;
    return vec4(
            primeSobolBurley(shuffledIndex, 0u, mixedSeed ^ 0x39468210u),
            primeSobolBurley(shuffledIndex, 1u, mixedSeed ^ 0xe9d8a845u),
            primeSobolBurley(shuffledIndex, 2u, mixedSeed ^ 0x5f32b482u),
            primeSobolBurley(shuffledIndex, 3u, mixedSeed ^ 0x1524cc56u));
}

float primeHashSample1D(
        PrimeSampleBase base, uint effect, uint streamIndex) {
    uint seed = primeEffectSeed(base, effect);
    seed = primeHashCombine(seed, base.sampleIndex);
    seed = primeHashCombine(seed, streamIndex);
    return primeHashToFloat(primeHash32(seed));
}

void primeBasis(vec3 normal, out vec3 tangent, out vec3 bitangent) {
    vec3 helper = abs(normal.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    tangent = normalize(cross(helper, normal));
    bitangent = cross(normal, tangent);
}

vec3 primeLocalToWorld(vec3 localDirection, vec3 normal) {
    vec3 tangent;
    vec3 bitangent;
    primeBasis(normal, tangent, bitangent);
    return normalize(tangent * localDirection.x + bitangent * localDirection.y
            + normal * localDirection.z);
}

vec3 primeWorldToLocal(vec3 worldDirection, vec3 normal) {
    vec3 tangent;
    vec3 bitangent;
    primeBasis(normal, tangent, bitangent);
    return vec3(dot(worldDirection, tangent), dot(worldDirection, bitangent),
            dot(worldDirection, normal));
}

#endif
