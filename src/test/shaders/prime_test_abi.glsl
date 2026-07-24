#ifndef PRIME_TEST_ABI_GLSL
#define PRIME_TEST_ABI_GLSL

const uint PRIME_TEST_WORD_BYTES = 16u;
const uint PRIME_TEST_SWEEP_WORDS = 1u;

const uint PRIME_TEST_FAILURE_NAN = 1u << 0u;
const uint PRIME_TEST_FAILURE_POSITIVE_INFINITY = 1u << 1u;
const uint PRIME_TEST_FAILURE_NEGATIVE_INFINITY = 1u << 2u;
const uint PRIME_TEST_FAILURE_RANGE = 1u << 3u;
const uint PRIME_TEST_FAILURE_UNIT_LENGTH = 1u << 4u;
const uint PRIME_TEST_FAILURE_HEMISPHERE = 1u << 5u;
const uint PRIME_TEST_FAILURE_ORTHOGONAL = 1u << 6u;
const uint PRIME_TEST_FAILURE_ROUND_TRIP = 1u << 7u;
const uint PRIME_TEST_FAILURE_IDENTITY = 1u << 8u;
const uint PRIME_TEST_FAILURE_SYMMETRY = 1u << 9u;
const uint PRIME_TEST_FAILURE_RECIPROCITY = 1u << 10u;
const uint PRIME_TEST_FAILURE_EVENT = 1u << 11u;
const uint PRIME_TEST_FAILURE_PDF = 1u << 12u;
const uint PRIME_TEST_FAILURE_VALUE = 1u << 13u;
const uint PRIME_TEST_FAILURE_STATE = 1u << 14u;
const uint PRIME_TEST_FAILURE_INDEX = 1u << 15u;

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

layout(set = 0, binding = 0, std430) readonly buffer PrimeTestInputBuffer {
    uvec4 words[];
} primeTestInput;

layout(set = 0, binding = 1, std430) writeonly buffer PrimeTestOutputBuffer {
    uvec4 words[];
} primeTestOutput;

bool primeTestBegin(out uint caseIndex) {
    caseIndex = gl_GlobalInvocationID.x;
    return caseIndex < primeTestInput.words[0].x;
}

uint primeTestInputOffset(uint caseIndex, uint wordsPerCase) {
    return 1u + caseIndex * wordsPerCase;
}

uint primeTestOutputOffset(uint caseIndex, uint wordsPerCase) {
    return caseIndex * wordsPerCase;
}

uint primeTestOutputWords() {
    return primeTestInput.words[0].y;
}

bool primeTestWitnessEnabled() {
    return primeTestOutputWords() > PRIME_TEST_SWEEP_WORDS;
}

uint primeTestClassify(float value) {
    if (isnan(value)) {
        return PRIME_TEST_FAILURE_NAN;
    }
    if (isinf(value)) {
        return value < 0.0
                ? PRIME_TEST_FAILURE_NEGATIVE_INFINITY
                : PRIME_TEST_FAILURE_POSITIVE_INFINITY;
    }
    return 0u;
}

uint primeTestClassify(vec2 value) {
    return primeTestClassify(value.x) | primeTestClassify(value.y);
}

uint primeTestClassify(vec3 value) {
    return primeTestClassify(value.x)
            | primeTestClassify(value.y)
            | primeTestClassify(value.z);
}

uint primeTestClassify(vec4 value) {
    return primeTestClassify(value.xyz) | primeTestClassify(value.w);
}

bool primeTestClose(float first, float second, float absoluteTolerance, float relativeTolerance) {
    if (primeTestClassify(first) != 0u || primeTestClassify(second) != 0u) {
        return false;
    }
    float scale = max(abs(first), abs(second));
    return abs(first - second) <= absoluteTolerance + relativeTolerance * scale;
}

bool primeTestClose(vec3 first, vec3 second, float absoluteTolerance, float relativeTolerance) {
    return primeTestClose(first.x, second.x, absoluteTolerance, relativeTolerance)
            && primeTestClose(first.y, second.y, absoluteTolerance, relativeTolerance)
            && primeTestClose(first.z, second.z, absoluteTolerance, relativeTolerance);
}

#endif
