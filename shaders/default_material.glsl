#ifndef PRIME_DEFAULT_MATERIAL_GLSL
#define PRIME_DEFAULT_MATERIAL_GLSL

// Canonical, source-neutral material state carried beyond the material adapter.
const uint PRIME_MATERIAL_FAMILY_MASK = 3u;
const uint PRIME_MATERIAL_FAMILY_OPAQUE = 0u;
const uint PRIME_MATERIAL_FAMILY_DIELECTRIC = 1u;
const uint PRIME_MATERIAL_FAMILY_FOLIAGE = 2u;
const uint PRIME_MATERIAL_MEDIUM_SHIFT = 2u;
const uint PRIME_MATERIAL_MEDIUM_MASK = 3u << PRIME_MATERIAL_MEDIUM_SHIFT;
const uint PRIME_MATERIAL_MEDIUM_NONE = 0u;
const uint PRIME_MATERIAL_MEDIUM_COLORLESS_GLASS = 1u << PRIME_MATERIAL_MEDIUM_SHIFT;
const uint PRIME_MATERIAL_MEDIUM_STAINED_GLASS = 2u << PRIME_MATERIAL_MEDIUM_SHIFT;
const uint PRIME_MATERIAL_MEDIUM_WATER = 3u << PRIME_MATERIAL_MEDIUM_SHIFT;
const uint PRIME_MATERIAL_THIN_WALLED = 1u << 4u;
const uint PRIME_MATERIAL_ANIMATED = 1u << 5u;
const uint PRIME_MATERIAL_VISIBLE_EMISSION = 1u << 6u;
const uint PRIME_MATERIAL_DECORATIVE_INTERFACE = 1u << 7u;

const uint PRIME_OPTICAL_FRESNEL_MASK = 0xffu;
const uint PRIME_OPTICAL_SUBSURFACE_SHIFT = 8u;
const uint PRIME_OPTICAL_POROSITY_SHIFT = 16u;
const uint PRIME_FRESNEL_DEFAULT_DIELECTRIC = 0u;
const uint PRIME_FRESNEL_IRON = 231u;
const uint PRIME_FRESNEL_GOLD = 232u;
const uint PRIME_FRESNEL_ALUMINIUM = 233u;
const uint PRIME_FRESNEL_CHROME = 234u;
const uint PRIME_FRESNEL_COPPER = 235u;
const uint PRIME_FRESNEL_LEAD = 236u;
const uint PRIME_FRESNEL_PLATINUM = 237u;
const uint PRIME_FRESNEL_SILVER = 238u;
const uint PRIME_FRESNEL_CUSTOM_CONDUCTOR = 239u;

const float PRIME_DEFAULT_DIELECTRIC_F0 = 0.04;
const float PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS = 0.9;
#ifndef PRIME_RUNTIME_DEFAULT_LINEAR_ROUGHNESS
#define PRIME_RUNTIME_DEFAULT_LINEAR_ROUGHNESS PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS
#endif

float primeDefaultLinearRoughness() {
    return clamp(PRIME_RUNTIME_DEFAULT_LINEAR_ROUGHNESS, 0.0, 1.0);
}

float primeBuiltinRoughness(uint materialClass) {
    const float values[13] = float[](
            0.9, 0.82, 0.48, 0.96, 0.72, 0.98, 0.62,
            0.28, 0.90, 0.38, 0.30, 0.34, 0.78);
    return materialClass < 13u
            ? values[materialClass]
            : primeDefaultLinearRoughness();
}

uint primeBuiltinFresnelCode(uint materialClass) {
    return materialClass == 9u
            ? PRIME_FRESNEL_IRON
            : materialClass == 10u
                    ? PRIME_FRESNEL_GOLD
                    : materialClass == 11u ? PRIME_FRESNEL_COPPER : 0u;
}

uint primeMaterialFamily(uint control) {
    return control & PRIME_MATERIAL_FAMILY_MASK;
}

uint primeMaterialMedium(uint control) {
    return control & PRIME_MATERIAL_MEDIUM_MASK;
}

bool primeMaterialIsTransmissive(uint control) {
    return primeMaterialFamily(control) == PRIME_MATERIAL_FAMILY_DIELECTRIC;
}

bool primeMaterialIsFoliage(uint control) {
    return primeMaterialFamily(control) == PRIME_MATERIAL_FAMILY_FOLIAGE;
}

bool primeMaterialIsColorlessGlass(uint control) {
    return primeMaterialMedium(control) == PRIME_MATERIAL_MEDIUM_COLORLESS_GLASS;
}

bool primeMaterialIsWater(uint control) {
    return primeMaterialMedium(control) == PRIME_MATERIAL_MEDIUM_WATER;
}

bool primeMaterialIsRoughGlass(uint control) {
    return (control & PRIME_MATERIAL_DECORATIVE_INTERFACE) != 0u;
}

bool primeFresnelIsConductor(uint fresnelCode) {
    return fresnelCode >= PRIME_FRESNEL_IRON
            && fresnelCode <= PRIME_FRESNEL_CUSTOM_CONDUCTOR;
}

float primeDecodeDielectricF0(uint fresnelCode) {
    return fresnelCode == PRIME_FRESNEL_DEFAULT_DIELECTRIC
            ? PRIME_DEFAULT_DIELECTRIC_F0
            : clamp(
                    float(fresnelCode - 1u) / 255.0,
                    0.02,
                    0.17);
}

struct PrimeFsrMasks {
    float reactive;
    float transparencyAndComposition;
};

PrimeFsrMasks primeFsrMasks(uint control) {
    PrimeFsrMasks masks;
    if (primeMaterialIsTransmissive(control)) {
        masks.reactive = 0.0;
        masks.transparencyAndComposition = 1.0;
        return masks;
    }
    masks.reactive = 0.0;
    masks.transparencyAndComposition =
            (control & PRIME_MATERIAL_ANIMATED) != 0u ? 0.75 : 0.0;
    return masks;
}

#endif
