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
// A standalone Area estimator owns the non-discrete direct-emitter term by itself. The realtime
// multi-candidate RIS proposal is not reverse-queried.
public static const uint PRIME_PATH_PREVIOUS_STANDALONE_AREA_NEE = 8u;
public static const uint PRIME_SUN_FAMILY_PROBABILITY_STEPS = 15u;
public static const uint PRIME_SUN_FAMILY_MINIMUM_MIXED_CODE = 2u;
public static const uint PRIME_SUN_FAMILY_MAXIMUM_MIXED_CODE = 13u;
public static const uint PRIME_WAVEFRONT_SURFACE_SUN_PROBABILITY_SHIFT = 8u;
public static const uint PRIME_WAVEFRONT_SURFACE_SUN_PROBABILITY_MASK = 0x1f00u;

// Importance is only a family-selection proxy. Preserve exact unsupported endpoints, but keep
// both proposals alive with bounded inverse weights whenever both can contribute.
public float primeQuantizedSunFamilyProbability(
        float sunImportance, float areaImportance) {
    bool hasSun = sunImportance > 0.0;
    bool hasArea = areaImportance > 0.0;
    if (!hasSun) return 0.0;
    if (!hasArea) return 1.0;
    float scale = max(sunImportance, areaImportance);
    float rawProbability = (sunImportance / scale)
            / (sunImportance / scale + areaImportance / scale);
    uint code = clamp(
            uint(round(rawProbability * float(PRIME_SUN_FAMILY_PROBABILITY_STEPS))),
            PRIME_SUN_FAMILY_MINIMUM_MIXED_CODE,
            PRIME_SUN_FAMILY_MAXIMUM_MIXED_CODE);
    return float(code) / float(PRIME_SUN_FAMILY_PROBABILITY_STEPS);
}

// Zero keeps the pre-existing "no adaptive family" state. Codes 0..15 are stored biased by one in
// PathState.sourceReserved, which is otherwise zero throughout transport.
public uint primeStoredSunFamilyProbability(float probability) {
    return 1u + uint(round(
            clamp(probability, 0.0, 1.0)
                    * float(PRIME_SUN_FAMILY_PROBABILITY_STEPS)));
}

public bool primeHasStoredLightFamilyProbability(uint storedProbability) {
    return storedProbability != 0u;
}

public float primeSunFamilyProbabilityFromStored(uint storedProbability) {
    uint code = storedProbability - 1u;
    return float(code) / float(PRIME_SUN_FAMILY_PROBABILITY_STEPS);
}

public float primeAreaFamilyProbabilityFromStored(uint storedProbability) {
    return 1.0 - primeSunFamilyProbabilityFromStored(storedProbability);
}

public uint primePackWavefrontSurfaceSunProbability(
        uint packedHitKind, uint storedProbability) {
    return (packedHitKind & ~PRIME_WAVEFRONT_SURFACE_SUN_PROBABILITY_MASK)
            | ((storedProbability << PRIME_WAVEFRONT_SURFACE_SUN_PROBABILITY_SHIFT)
                    & PRIME_WAVEFRONT_SURFACE_SUN_PROBABILITY_MASK);
}

public uint primeWavefrontSurfaceSunProbability(uint packedHitKind) {
    return (packedHitKind & PRIME_WAVEFRONT_SURFACE_SUN_PROBABILITY_MASK)
            >> PRIME_WAVEFRONT_SURFACE_SUN_PROBABILITY_SHIFT;
}

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
