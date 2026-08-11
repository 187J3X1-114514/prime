#language slang 2026
module "core/transparent_checkerboard.slang";

static const float PRIME_TRANSPARENT_CHECKERBOARD_BRANCH_PROBABILITY = 0.5;

public bool primeTransparentCheckerboardSamplesReflection(uint2 pixel, uint sampleIndex)
{
    return ((pixel.x ^ pixel.y ^ sampleIndex) & 1) == 0;
}

public float primeTransparentCheckerboardPdf(float conditionalPdf)
{
    return conditionalPdf * PRIME_TRANSPARENT_CHECKERBOARD_BRANCH_PROBABILITY;
}
