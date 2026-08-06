#ifndef PRIME_DEFAULT_MATERIAL_GLSL
#define PRIME_DEFAULT_MATERIAL_GLSL

// Shared contract for vanilla terrain until a LabPBR decoder supplies explicit parameters.
// Keep this independent of atlas declarations: raygen and the post-raygen NRD preparation pass
// both consume it, and neither side may silently derive a different roughness or FSR mask.
const uint PRIME_MATERIAL_FLAG_CUTOUT = 1u;
const uint PRIME_MATERIAL_FLAG_ANIMATED_TEXTURE = 2u;
const uint PRIME_MATERIAL_FLAG_TRANSMISSIVE = 4u;
const uint PRIME_MATERIAL_FLAG_THIN_WALLED = 8u;
const uint PRIME_MATERIAL_FLAG_WATER = 16u;
const uint PRIME_MATERIAL_FLAG_FOLIAGE = 32u;
const uint PRIME_MATERIAL_FLAG_LABPBR_NORMAL = 64u;
const uint PRIME_MATERIAL_FLAG_LABPBR_SPECULAR = 128u;
const uint PRIME_MATERIAL_FLAG_TANGENT_NEGATIVE = 256u;
// Per-texel flag produced by the LabPBR decoder. Unlike the atlas-presence bits above this is
// never stored in terrain geometry; it follows the sampled green channel into NRD and FSR guides.
const uint PRIME_MATERIAL_FLAG_LABPBR_METAL = 512u;
// Full-bright dynamic surfaces contribute only when actually hit; neither light tree samples them.
const uint PRIME_MATERIAL_FLAG_VISIBLE_EMISSION = 1024u;
// Per-texel glass semantics produced by material.glsl; neither bit is stored in terrain geometry.
const uint PRIME_MATERIAL_FLAG_COLORLESS_GLASS = 2048u;
const uint PRIME_MATERIAL_FLAG_ROUGH_GLASS = 4096u;

const float PRIME_DEFAULT_DIELECTRIC_F0 = 0.04;
// Standalone preparation shaders do not share the ray-tracing push constants. They use this
// reference only for invalid/sky fallbacks; ray-tracing stages replace it with the user setting.
const float PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS = 0.9;
#ifndef PRIME_RUNTIME_DEFAULT_LINEAR_ROUGHNESS
#define PRIME_RUNTIME_DEFAULT_LINEAR_ROUGHNESS PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS
#endif

float primeDefaultLinearRoughness() {
    // Base color is not a material parameter: equally bright texels can represent stone, cloth,
    // paint, or metal. Missing roughness therefore has one explicit, user-controlled meaning.
    return clamp(PRIME_RUNTIME_DEFAULT_LINEAR_ROUGHNESS, 0.0, 1.0);
}

bool primeMaterialIsTransmissive(uint flags) {
    return (flags & PRIME_MATERIAL_FLAG_TRANSMISSIVE) != 0u;
}

bool primeMaterialIsFoliage(uint flags) {
    return (flags & PRIME_MATERIAL_FLAG_FOLIAGE) != 0u;
}

bool primeMaterialIsColorlessGlass(uint flags) {
    return (flags & PRIME_MATERIAL_FLAG_COLORLESS_GLASS) != 0u;
}

bool primeMaterialIsRoughGlass(uint flags) {
    return (flags & PRIME_MATERIAL_FLAG_ROUGH_GLASS) != 0u;
}

float primeMaterialLinearRoughness(uint flags) {
    if (primeMaterialIsTransmissive(flags)) {
        // Water and glass body texels are exact interfaces. The decorative stained-glass texels
        // selected by material.glsl intentionally use the configured fallback when no LabPBR
        // roughness is authored.
        return primeMaterialIsRoughGlass(flags)
                ? primeDefaultLinearRoughness()
                : 0.0;
    }
    return primeDefaultLinearRoughness();
}

float primeMaterialDielectricF0(uint flags) {
    // Water uses eta=1.333; other vanilla translucent models use the ordinary glass boundary.
    return (flags & PRIME_MATERIAL_FLAG_WATER) != 0u ? 0.02037 : PRIME_DEFAULT_DIELECTRIC_F0;
}

struct PrimeFsrMasks {
    float reactive;
    float transparencyAndComposition;
};

PrimeFsrMasks primeFsrMasks(uint flags) {
    PrimeFsrMasks masks;
    if (primeMaterialIsTransmissive(flags)) {
        // The traced composite writes interface depth and motion. FidelityFX recommends the T&C
        // mask as the softer alternative for reflective shading whose color motion can differ;
        // a near-one reactive mask would instead discard history and expose the FSR jitter.
        masks.reactive = 0.0;
        masks.transparencyAndComposition = 1.0;
        return masks;
    }
    // Alpha-tested foliage writes matching depth and motion. Marking it here would fully remove
    // FSR's thin-feature lock and turn sub-pixel coverage changes into visible edge shimmer.
    masks.reactive = 0.0;
    masks.transparencyAndComposition =
            (flags & PRIME_MATERIAL_FLAG_ANIMATED_TEXTURE) != 0u ? 0.75 : 0.0;
    return masks;
}

#endif
