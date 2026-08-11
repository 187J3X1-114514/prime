#ifndef PRIME_TRANSPORT_MATH_SLANG
#define PRIME_TRANSPORT_MATH_SLANG

float primeProductOver(float first, float second, float denominator)
{
    int firstExponent;
    int secondExponent;
    int denominatorExponent;
    float significand = frexp(first, firstExponent)
            * frexp(second, secondExponent)
            / frexp(denominator, denominatorExponent);
    return ldexp(significand, firstExponent + secondExponent - denominatorExponent);
}

float3 primeProductOver(float3 first, float3 second, float denominator)
{
    int3 firstExponent;
    int3 secondExponent;
    int denominatorExponent;
    float3 significand = frexp(first, firstExponent)
            * frexp(second, secondExponent)
            / frexp(denominator, denominatorExponent);
    return ldexp(
            significand,
            firstExponent + secondExponent - int3(denominatorExponent));
}

float3 primeTripleProduct(float3 first, float3 second, float third)
{
    int3 firstExponent;
    int3 secondExponent;
    int thirdExponent;
    float3 significand = frexp(first, firstExponent)
            * frexp(second, secondExponent)
            * frexp(third, thirdExponent);
    return ldexp(significand, firstExponent + secondExponent + int3(thirdExponent));
}

float primePowerHeuristicValue(float firstPdf, float secondPdf)
{
    if (firstPdf <= 0.0) return 0.0;
    if (secondPdf <= 0.0) return 1.0;
    if (firstPdf >= secondPdf) {
        float ratio = secondPdf / firstPdf;
        return 1.0 / (1.0 + ratio * ratio);
    }
    float ratio = firstPdf / secondPdf;
    float ratioSquared = ratio * ratio;
    return ratioSquared / (1.0 + ratioSquared);
}

float primePowerHeuristicOverPdfValue(float sampledPdf, float otherPdf)
{
    if (sampledPdf <= 0.0) return 0.0;
    if (otherPdf <= 0.0) return 1.0 / sampledPdf;
    if (sampledPdf >= otherPdf) {
        float ratio = otherPdf / sampledPdf;
        return (1.0 / sampledPdf) / (1.0 + ratio * ratio);
    }
    float ratio = sampledPdf / otherPdf;
    return (ratio / otherPdf) / (1.0 + ratio * ratio);
}

float primeAreaHitMisWeightValue(
        bool previousUsedAreaNee,
        bool previousCannotUseMis,
        float bsdfPdf,
        float lightPdf)
{
    return !previousUsedAreaNee || previousCannotUseMis
            ? 1.0
            : primePowerHeuristicValue(bsdfPdf, lightPdf);
}

float primeAreaSolidAnglePdfValue(
        float areaPdf, float distanceSquared, float lightCosine)
{
    if (distanceSquared <= 0.0 || lightCosine <= 0.0 || areaPdf <= 0.0) return 0.0;
    return primeProductOver(areaPdf, distanceSquared, lightCosine);
}

float3 primeSegmentTransmittance(float3 extinction, float distance)
{
    return exp(-extinction * distance);
}

float primeEtaScaleAfterScatter(float etaScale, float relativeEta, bool transmission)
{
    return transmission ? etaScale * relativeEta * relativeEta : etaScale;
}

float primeRussianRouletteMetric(float3 throughput, float etaScale)
{
    return max(throughput.r, max(throughput.g, throughput.b)) * etaScale;
}

float primeRussianRouletteSurvival(float3 throughput, float etaScale)
{
    return min(max(primeRussianRouletteMetric(throughput, etaScale), 0.0), 1.0);
}

bool primeRussianRouletteApplies(uint completedScatters, uint minimumCompletedScatters)
{
    return completedScatters >= minimumCompletedScatters;
}

float primeOfflineRussianRouletteSurvival(float3 throughput, float etaScale)
{
    return min(sqrt(max(primeRussianRouletteMetric(throughput, etaScale), 0.0)), 1.0);
}

uint primePackWavefrontEtaScale(float etaScale)
{
    return packHalf2x16(float2(etaScale, 0.0)) & 0xffff;
}

float primeUnpackWavefrontEtaScale(uint packedEtaScale)
{
    return unpackHalf2x16ToFloat(packedEtaScale & 0xffff).x;
}

float3 primeRussianRouletteReweight(float3 throughput, float survival)
{
    return throughput / survival;
}

#endif
