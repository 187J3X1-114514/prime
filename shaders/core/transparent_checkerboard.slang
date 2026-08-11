#ifndef PRIME_TRANSPARENT_CHECKERBOARD_SLANG
#define PRIME_TRANSPARENT_CHECKERBOARD_SLANG

static const float PRIME_TRANSPARENT_CHECKERBOARD_BRANCH_PROBABILITY = 0.5;

bool primeTransparentCheckerboardSamplesReflection(uint2 pixel, uint sampleIndex)
{
    return ((pixel.x ^ pixel.y ^ sampleIndex) & 1) == 0;
}

float primeTransparentCheckerboardPdf(float conditionalPdf)
{
    return conditionalPdf * PRIME_TRANSPARENT_CHECKERBOARD_BRANCH_PROBABILITY;
}

#endif
