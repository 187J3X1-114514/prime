#ifndef PRIME_LIGHTWEIGHT_SAMPLING_GLSL
#define PRIME_LIGHTWEIGHT_SAMPLING_GLSL

const float PRIME_LIGHTWEIGHT_TRANSPARENT_BRANCH_PROBABILITY = 0.5;

bool primeLightweightPrimarySamplesReflection(uvec2 pixel, uint sampleIndex) {
    return ((pixel.x ^ pixel.y ^ sampleIndex) & 1u) == 0u;
}

float primeLightweightTransparentBranchPdf(float conditionalPdf) {
    return conditionalPdf * PRIME_LIGHTWEIGHT_TRANSPARENT_BRANCH_PROBABILITY;
}

#endif
