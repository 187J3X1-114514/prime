#ifndef PRIME_TRANSPARENT_CHECKERBOARD_GLSL
#define PRIME_TRANSPARENT_CHECKERBOARD_GLSL

const float PRIME_TRANSPARENT_CHECKERBOARD_BRANCH_PROBABILITY = 0.5;

bool primeTransparentCheckerboardSamplesReflection(
        uvec2 pixel, uint sampleIndex) {
    return ((pixel.x ^ pixel.y ^ sampleIndex) & 1u) == 0u;
}

float primeTransparentCheckerboardPdf(float conditionalPdf) {
    return conditionalPdf
            * PRIME_TRANSPARENT_CHECKERBOARD_BRANCH_PROBABILITY;
}

#endif
