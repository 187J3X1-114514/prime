#language slang 2026
module "core/transport_math.slang";

public float primeProductOver(float first, float second, float denominator)
{
    int firstExponent;
    int secondExponent;
    int denominatorExponent;
    float significand = frexp(first, firstExponent)
            * frexp(second, secondExponent)
            / frexp(denominator, denominatorExponent);
    return ldexp(significand, firstExponent + secondExponent - denominatorExponent);
}

public float3 primeProductOver(float3 first, float3 second, float denominator)
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

public float3 primeTripleProduct(float3 first, float3 second, float third)
{
    int3 firstExponent;
    int3 secondExponent;
    int thirdExponent;
    float3 significand = frexp(first, firstExponent)
            * frexp(second, secondExponent)
            * frexp(third, thirdExponent);
    return ldexp(significand, firstExponent + secondExponent + int3(thirdExponent));
}

public float primePowerHeuristicValue(float firstPdf, float secondPdf)
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

public float primePowerHeuristicOverPdfValue(float sampledPdf, float otherPdf)
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

public float primeLightFamilyPdfValue(float conditionalPdf, float familyProbability)
{
    return conditionalPdf * familyProbability;
}

public float primeRisScaledWeightValue(
        float target, float minimumProposalPdf, float proposalPdf)
{
    return target > 0.0 && minimumProposalPdf > 0.0 && proposalPdf > 0.0
            ? target * (minimumProposalPdf / proposalPdf)
            : 0.0;
}

public uint primeRisSelectIndex(float4 scaledWeights, float sampleValue)
{
    float total = scaledWeights.x + scaledWeights.y
            + scaledWeights.z + scaledWeights.w;
    if (!(total > 0.0)) return 0u;
    float threshold = min(sampleValue, asfloat(0x3f7fffffu)) * total;
    float cumulative = scaledWeights.x;
    if (threshold < cumulative) return 0u;
    cumulative += scaledWeights.y;
    if (threshold < cumulative) return 1u;
    cumulative += scaledWeights.z;
    return threshold < cumulative ? 2u : 3u;
}

public float primeRisEffectivePdfValue(
        float conditionalPdf,
        float selectedTarget,
        float minimumProposalPdf,
        float scaledWeightSum,
        uint candidateCount)
{
    if (!(conditionalPdf > 0.0)
            || !(selectedTarget > 0.0)
            || !(minimumProposalPdf > 0.0)
            || !(scaledWeightSum > 0.0)
            || candidateCount == 0u) {
        return 0.0;
    }
    return primeProductOver(
            conditionalPdf,
            float(candidateCount) * selectedTarget * minimumProposalPdf,
            scaledWeightSum);
}

public float primeAreaHitMisWeightValue(
        bool previousUsedAreaNee,
        bool previousCannotUseMis,
        float bsdfPdf,
        float lightPdf)
{
    return !previousUsedAreaNee || previousCannotUseMis
            ? 1.0
            : primePowerHeuristicValue(bsdfPdf, lightPdf);
}

public float primeAreaSolidAnglePdfValue(
        float areaPdf, float distanceSquared, float lightCosine)
{
    if (distanceSquared <= 0.0 || lightCosine <= 0.0 || areaPdf <= 0.0) return 0.0;
    return primeProductOver(areaPdf, distanceSquared, lightCosine);
}

public float3 primeSegmentTransmittance(float3 extinction, float distance)
{
    return exp(-extinction * distance);
}

public float primeEtaScaleAfterScatter(float etaScale, float relativeEta, bool transmission)
{
    return transmission ? etaScale * relativeEta * relativeEta : etaScale;
}

public float primeRussianRouletteMetric(float3 throughput, float etaScale)
{
    return max(throughput.r, max(throughput.g, throughput.b)) * etaScale;
}

public float primeRussianRouletteSurvival(float3 throughput, float etaScale)
{
    return min(sqrt(max(primeRussianRouletteMetric(throughput, etaScale), 0.0)), 1.0);
}

public bool primeRussianRouletteApplies(uint completedScatters, uint minimumCompletedScatters)
{
    return completedScatters >= minimumCompletedScatters;
}

public uint primePackWavefrontEtaScale(float etaScale)
{
    return packHalf2x16(float2(etaScale, 0.0)) & 0xffff;
}

public float primeUnpackWavefrontEtaScale(uint packedEtaScale)
{
    return unpackHalf2x16ToFloat(packedEtaScale & 0xffff).x;
}

public float3 primeRussianRouletteReweight(float3 throughput, float survival)
{
    return throughput / survival;
}
