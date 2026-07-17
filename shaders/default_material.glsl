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

const float PRIME_DEFAULT_DIELECTRIC_F0 = 0.04;
const float PRIME_DEFAULT_MIN_LINEAR_ROUGHNESS = 0.70;
const float PRIME_DEFAULT_MAX_LINEAR_ROUGHNESS = 0.90;
const float PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS = 0.8;
const vec3 PRIME_REC2020_LUMINANCE = vec3(0.2627, 0.6780, 0.0593);

float primeDefaultLinearRoughness(vec3 baseColor) {
    float luminance = dot(clamp(baseColor, 0.0, 1.0), PRIME_REC2020_LUMINANCE);
    float brightness = smoothstep(0.08, 0.90, luminance);
    return mix(
            PRIME_DEFAULT_MAX_LINEAR_ROUGHNESS,
            PRIME_DEFAULT_MIN_LINEAR_ROUGHNESS,
            brightness);
}

bool primeMaterialIsTransmissive(uint flags) {
    return (flags & PRIME_MATERIAL_FLAG_TRANSMISSIVE) != 0u;
}

bool primeMaterialIsFoliage(uint flags) {
    return (flags & PRIME_MATERIAL_FLAG_FOLIAGE) != 0u;
}

float primeMaterialLinearRoughness(vec3 baseColor, uint flags) {
    if (primeMaterialIsTransmissive(flags)) {
        // Vanilla glass, panes and water have no authored micro-normal distribution. Treating
        // their visually sharp interface as a tiny non-zero GGX lobe creates stochastic tail
        // samples and fireflies without representing any Minecraft material detail. Zero is a
        // semantic contract: the visible interface is split into deterministic reflection and
        // transmission paths. A future explicit material roughness > 0 uses one unsplit BSDF path.
        return 0.0;
    }
    return primeDefaultLinearRoughness(baseColor);
}

float primeMaterialDielectricF0(uint flags) {
    // Water uses eta=1.333; other vanilla translucent models use the ordinary glass boundary.
    return (flags & PRIME_MATERIAL_FLAG_WATER) != 0u ? 0.02037 : PRIME_DEFAULT_DIELECTRIC_F0;
}

float primeFsrTransparencyAndCompositionMask(uint flags, float linearRoughness) {
    if (primeMaterialIsTransmissive(flags) || primeMaterialIsFoliage(flags)) {
        return 1.0;
    }
    float animated = (flags & PRIME_MATERIAL_FLAG_ANIMATED_TEXTURE) != 0u ? 0.75 : 0.0;
    // Reserved for the smooth reflective materials introduced by the future LabPBR decoder. The
    // default Minecraft roughness range is 0.7..0.9, so ordinary terrain correctly evaluates to
    // zero and does not lose temporal stability.
    float hardToTrackReflection = 0.5 * (1.0 - smoothstep(0.20, 0.45, linearRoughness));
    return max(animated, hardToTrackReflection);
}

#endif
