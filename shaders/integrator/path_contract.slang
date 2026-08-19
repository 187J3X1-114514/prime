#language slang 2026
module "integrator/path_contract.slang";

// Stable semantic values shared by hit shaders, integrator stages, and queued path storage.
public static const uint PRIME_HIT_NONE = 0u;
public static const uint PRIME_HIT_SURFACE = 1u;
public static const uint PRIME_NO_PRIMITIVE_INDEX = 0xffffffffu;
public static const uint PRIME_PATH_PREVIOUS_DISCRETE = 1u;
// The previous realtime vertex deliberately omitted Sun NEE. A later sun-disk hit therefore has no
// competing light technique and must retain unit weight.
public static const uint PRIME_PATH_PREVIOUS_NO_SUN_NEE = 2u;
// The previous realtime vertex sampled one member of the Sun/Area family mixture. Reverse MIS
// must use the same family probability as the forward one-sample estimator.
public static const uint PRIME_PATH_PREVIOUS_MIXED_LIGHT_NEE = 4u;
// A standalone Area estimator owns the non-discrete direct-emitter term by itself. The realtime
// multi-candidate RIS proposal is not reverse-queried.
public static const uint PRIME_PATH_PREVIOUS_STANDALONE_AREA_NEE = 8u;
public static const float PRIME_SECONDARY_LIGHT_FAMILY_PROBABILITY = 0.5;

public bool primePreviousSunNeeEnabled(uint pathFlags) {
    return (pathFlags & PRIME_PATH_PREVIOUS_NO_SUN_NEE) == 0u;
}

public bool primeStandaloneAreaHitSuppressed(
        bool previousUsedAreaNee,
        bool previousCannotUseMis,
        uint pathFlags) {
    return previousUsedAreaNee
            && !previousCannotUseMis
            && (pathFlags & PRIME_PATH_PREVIOUS_STANDALONE_AREA_NEE) != 0u;
}

// A transparent path that still shares its reconstruction observation may use roulette only after
// it captures a stable solid-angle guide. Once it diverges, an independent probe owns the guide and
// removes this transport lifetime constraint.
public bool primeTransparentTransportOwnsGuide(
        bool permanentOwner, bool independentProbeFallback) {
    return permanentOwner || !independentProbeFallback;
}

public bool primeTransparentPrimaryReflectionOnly(
        bool discreteOnly, bool hasTransmission, bool hasReflection) {
    return discreteOnly && !hasTransmission && hasReflection;
}

public bool primeTransparentRouletteBlocked(
        bool pathOwnsGuide, bool guideEnabled, bool hasGuide) {
    return pathOwnsGuide && guideEnabled && !hasGuide;
}
