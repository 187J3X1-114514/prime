#ifndef PRIME_TRANSPORT_MATH_GLSL
#define PRIME_TRANSPORT_MATH_GLSL

// Pure transport arithmetic shared by the runtime integrator and compute property tests. Callers
// own input validation; keeping diagnostics and renderer state outside this file makes every
// numerical contract directly executable without reconstructing the ray-tracing ABI.

// Power-of-two scaling preserves a representable a*b/c when either intermediate operation would
// overflow or underflow. frexp/ldexp transfer exponents exactly; only the significand operations
// and final result round.
float primeProductOver(float first, float second, float denominator) {
    int firstExponent;
    int secondExponent;
    int denominatorExponent;
    float significand = frexp(first, firstExponent)
            * frexp(second, secondExponent)
            / frexp(denominator, denominatorExponent);
    return ldexp(significand, firstExponent + secondExponent - denominatorExponent);
}

vec3 primeProductOver(vec3 first, vec3 second, float denominator) {
    ivec3 firstExponent;
    ivec3 secondExponent;
    int denominatorExponent;
    vec3 significand = frexp(first, firstExponent)
            * frexp(second, secondExponent)
            / frexp(denominator, denominatorExponent);
    return ldexp(
            significand,
            firstExponent + secondExponent - ivec3(denominatorExponent));
}

vec3 primeTripleProduct(vec3 first, vec3 second, float third) {
    ivec3 firstExponent;
    ivec3 secondExponent;
    int thirdExponent;
    vec3 significand = frexp(first, firstExponent)
            * frexp(second, secondExponent)
            * frexp(third, thirdExponent);
    return ldexp(significand, firstExponent + secondExponent + ivec3(thirdExponent));
}

float primePowerHeuristicValue(float firstPdf, float secondPdf) {
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

float primePowerHeuristicOverPdfValue(float sampledPdf, float otherPdf) {
    if (sampledPdf <= 0.0) return 0.0;
    if (otherPdf <= 0.0) return 1.0 / sampledPdf;
    if (sampledPdf >= otherPdf) {
        float ratio = otherPdf / sampledPdf;
        return (1.0 / sampledPdf) / (1.0 + ratio * ratio);
    }
    float ratio = sampledPdf / otherPdf;
    return (ratio / otherPdf) / (1.0 + ratio * ratio);
}

float primeAreaSolidAnglePdfValue(
        float areaPdf, float distanceSquared, float lightCosine) {
    if (distanceSquared <= 0.0 || lightCosine <= 0.0 || areaPdf <= 0.0) {
        return 0.0;
    }
    return primeProductOver(areaPdf, distanceSquared, lightCosine);
}

vec3 primeSegmentTransmittance(vec3 extinction, float distance) {
    return exp(-extinction * distance);
}

float primeRussianRouletteSurvival(vec3 throughput) {
    return clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.05, 0.95);
}

vec3 primeRussianRouletteReweight(vec3 throughput, float survival) {
    return throughput / survival;
}

#endif
