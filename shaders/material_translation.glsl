#ifndef PRIME_MATERIAL_TRANSLATION_GLSL
#define PRIME_MATERIAL_TRANSLATION_GLSL

#include "default_material.glsl"
#include "labpbr.glsl"

// Community texture formats are transport encodings, not Prime's physical material model.
// This is the only boundary allowed to turn LabPBR channel values into BSDF parameters. Unknown
// encodings deliberately lose their effect instead of being assigned a plausible but false one.
struct PrimeTranslatedLabPbrMaterial {
    float perceptualRoughness;
    float linearRoughness;
    float dielectricF0;
    float subsurfaceWeight;
    uint metalId;
    uint thinWalled;
};

const float PRIME_COMMON_DIELECTRIC_F0_MINIMUM = 0.02;
const float PRIME_COMMON_DIELECTRIC_F0_MAXIMUM = 0.17;

bool primeLabPbrIsStandardMetalId(uint metalId) {
    return metalId >= 230u && metalId <= 237u;
}

bool primeLabPbrIsCustomMetalId(uint metalId) {
    return metalId == 255u;
}

bool primeLabPbrIsSupportedMetalId(uint metalId) {
    return primeLabPbrIsStandardMetalId(metalId)
            || primeLabPbrIsCustomMetalId(metalId);
}

PrimeTranslatedLabPbrMaterial primeTranslateLabPbr(
        PrimeLabPbrSample encoded,
        uint flags) {
    bool authored = primeHasLabPbrSpecular(flags);
    bool transmissive = primeMaterialIsTransmissive(flags);
    bool safeThinSubsurface = authored
            && (flags & PRIME_MATERIAL_FLAG_CUTOUT) != 0u
            && encoded.subsurface > 0.0;

    PrimeTranslatedLabPbrMaterial result;
    result.perceptualRoughness = authored
            ? clamp(encoded.perceptualRoughness, 0.0, 1.0)
            : primeDefaultLinearRoughness();
    result.linearRoughness = result.perceptualRoughness * result.perceptualRoughness;

    // LabPBR gives G=255 an explicit custom-metal meaning: the albedo texture is normal-incidence
    // reflectance (F0), not diffuse color. Preserve that defined transport semantic alongside the
    // eight predefined metals. Reserved IDs 238..254 and metal semantics on transmissive models
    // remain deliberately ignored.
    result.metalId = authored
            && !transmissive
            && primeLabPbrIsSupportedMetalId(encoded.metalId)
            ? encoded.metalId
            : 0u;

    // G=0..229 is a dielectric F0 channel. Clamp it to Prime's deliberately broad non-metal
    // boundary: this accepts uncommon but plausible dielectrics without interpreting them as
    // metals. Every metal/reserved encoding has no dielectric interpretation and falls back to
    // Prime's ordinary dielectric boundary if another material rule prevents metal translation.
    result.dielectricF0 = authored && encoded.metalId == 0u
            ? clamp(
                    encoded.dielectricF0,
                    PRIME_COMMON_DIELECTRIC_F0_MINIMUM,
                    PRIME_COMMON_DIELECTRIC_F0_MAXIMUM)
            : PRIME_DEFAULT_DIELECTRIC_F0;

    // Alpha-cut geometry already denotes a zero-thickness surface in Minecraft. Its SSS hint can
    // safely select RoboCute's thin-wall closure. Solid/translucent SSS has no mean-free-path or
    // volume semantics in LabPBR, so conservatively discard it.
    result.subsurfaceWeight = safeThinSubsurface
            ? clamp(encoded.subsurface, 0.0, 1.0)
            : 0.0;
    result.thinWalled = safeThinSubsurface ? 1u : 0u;
    return result;
}

PrimeTranslatedLabPbrMaterial primeDecodeAndTranslateLabPbr(
        uint packedNormal,
        uint packedSpecular,
        uint flags) {
    return primeTranslateLabPbr(
            primeDecodeLabPbr(packedNormal, packedSpecular, flags),
            flags);
}

bool primeTranslatedLabPbrIsMetal(PrimeTranslatedLabPbrMaterial material) {
    return material.metalId != 0u;
}

#endif
