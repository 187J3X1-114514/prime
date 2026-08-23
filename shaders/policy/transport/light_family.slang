#language slang 2026
module "policy/transport/light_family.slang";

import "contract/path.slang";

// Importance only selects a family. Exact unsupported endpoints remain exact while mixed
// proposals keep bounded inverse weights.
public float primeQuantizedSunFamilyProbability(
        float sunImportance,
        float areaImportance) {
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

public uint primeStoredSunFamilyProbability(float probability) {
    return 1u + uint(round(clamp(probability, 0.0, 1.0)
            * float(PRIME_SUN_FAMILY_PROBABILITY_STEPS)));
}

public bool primeHasStoredLightFamilyProbability(uint storedProbability) {
    return storedProbability != 0u;
}

public float primeSunFamilyProbabilityFromStored(uint storedProbability) {
    return float(storedProbability - 1u)
            / float(PRIME_SUN_FAMILY_PROBABILITY_STEPS);
}

public float primeAreaFamilyProbabilityFromStored(uint storedProbability) {
    return 1.0 - primeSunFamilyProbabilityFromStored(storedProbability);
}

public uint primePackWavefrontSurfaceSunProbability(
        uint packedHitKind,
        uint storedProbability) {
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
