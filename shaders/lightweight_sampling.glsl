#ifndef PRIME_LIGHTWEIGHT_SAMPLING_GLSL
#define PRIME_LIGHTWEIGHT_SAMPLING_GLSL

const float PRIME_LIGHTWEIGHT_TRANSPARENT_BRANCH_PROBABILITY = 0.5;

#if defined(PRIME_LIGHTWEIGHT_ABI)
uint primeLightweightMaximumScatters() {
    return clamp(
            primeMaximumBounces(),
            1u,
            PRIME_LIGHTWEIGHT_MAXIMUM_SCATTERS);
}
#endif

bool primeLightweightPrimarySamplesReflection(uvec2 pixel, uint sampleIndex) {
    return ((pixel.x ^ pixel.y ^ sampleIndex) & 1u) == 0u;
}

float primeLightweightTransparentBranchPdf(float conditionalPdf) {
    return conditionalPdf * PRIME_LIGHTWEIGHT_TRANSPARENT_BRANCH_PROBABILITY;
}

#endif
