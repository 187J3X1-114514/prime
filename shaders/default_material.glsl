#ifndef PRIME_DEFAULT_MATERIAL_GLSL
#define PRIME_DEFAULT_MATERIAL_GLSL

// Shared contract for vanilla terrain until a LabPBR decoder supplies explicit parameters.
// Keep this independent of atlas declarations: raygen and the post-raygen NRD preparation pass
// both consume it, and neither side may silently derive a different roughness or FSR mask.
const uint PRIME_MATERIAL_FLAG_CUTOUT = 1u;
const uint PRIME_MATERIAL_FLAG_ANIMATED_TEXTURE = 2u;

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

float primeFsrTransparencyAndCompositionMask(uint flags, float linearRoughness) {
    float animated = (flags & PRIME_MATERIAL_FLAG_ANIMATED_TEXTURE) != 0u ? 0.75 : 0.0;
    // Reserved for the smooth reflective materials introduced by the future LabPBR decoder. The
    // default Minecraft roughness range is 0.7..0.9, so ordinary terrain correctly evaluates to
    // zero and does not lose temporal stability.
    float hardToTrackReflection = 0.5 * (1.0 - smoothstep(0.20, 0.45, linearRoughness));
    return max(animated, hardToTrackReflection);
}

#endif
