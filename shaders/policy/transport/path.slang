#language slang 2026
module "policy/transport/path.slang";

// Steady bounce zero begins after the first non-delta scatter. Delta-chain length deliberately
// does not participate in roulette or in the steady dispatch limit.
public uint primeTwoStageRouletteCompletedScatters(uint steadyBounce) {
    return steadyBounce + 1u;
}

// A shared transparent observation blocks roulette until it has a stable solid-angle guide.
public bool primeTransparentTransportOwnsGuide(
        bool permanentOwner,
        bool independentProbeFallback) {
    return permanentOwner || !independentProbeFallback;
}

public bool primeTransparentPrimaryReflectionOnly(
        bool discreteOnly,
        bool hasTransmission,
        bool hasReflection) {
    return discreteOnly && !hasTransmission && hasReflection;
}

public bool primeTransparentRouletteBlocked(
        bool pathOwnsGuide,
        bool guideEnabled,
        bool hasGuide) {
    return pathOwnsGuide && guideEnabled && !hasGuide;
}
