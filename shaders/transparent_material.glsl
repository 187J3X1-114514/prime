#ifndef PRIME_TRANSPARENT_MATERIAL_GLSL
#define PRIME_TRANSPARENT_MATERIAL_GLSL

#include "color_space.glsl"

// Rec.2020's near-monochromatic primaries are 630, 532 and 467 nm. Pope and Fry's measured
// absorption coefficients for pure water at 22 C are 0.2916 m^-1 at 630 nm and, by linear
// interpolation of their Table 3, 0.04444 m^-1 at 532 nm and 0.010182 m^-1 at 467 nm.
const vec3 PRIME_PURE_WATER_ABSORPTION_M_INV = vec3(0.2916, 0.04444, 0.010182);
const float PRIME_THIN_WALLED_THICKNESS_M = 0.0625;

// Calibrate one metre of glass to vanilla's encoded-sRGB alpha blend over white. Storing that
// single composite as volume transmittance preserves the authored neutral density without
// reintroducing a filter at each geometric interface.
vec3 primeGlassVolumeTransmittance(vec3 baseColor, float opacity) {
    vec3 linearSrgb = clamp(
            primeLinearRec2020ToLinearBt709(baseColor),
            vec3(0.0),
            vec3(1.0));
    vec3 encodedSrgb = primeEncodeSrgb(linearSrgb);
    float coverage = clamp(opacity, 0.0, 1.0);
    vec3 encodedOverWhite = mix(vec3(1.0), encodedSrgb, coverage);
    return clamp(
            primeLinearSrgbToLinearRec2020(primeDecodeSrgb(encodedOverWhite)),
            vec3(0.0),
            vec3(1.0));
}

vec3 primeGlassVolumeExtinction(vec3 baseColor, float opacity) {
    // Match RoboCute's transmission-volume floor without modifying the protected reference port.
    return -log(max(vec3(1.0e-3), primeGlassVolumeTransmittance(baseColor, opacity)));
}

vec3 primeGlassShadowExtinction(vec3 referenceBaseColor, float referenceOpacity) {
    // The caller supplies one position-independent material reference so a closed medium has
    // identical extinction at both boundaries. Geometric path length then owns shadow depth.
    return primeGlassVolumeExtinction(referenceBaseColor, referenceOpacity);
}

vec3 primeShadowBoundaryOpticalDepth(
        vec3 currentOpticalDepth,
        vec3 extinction,
        float rayDistance,
        float hitDistance,
        bool entering) {
    float remaining = max(rayDistance - hitDistance, 0.0);
    return currentOpticalDepth + extinction * (entering ? remaining : -remaining);
}

vec3 primeShadowThinOpticalDepth(
        vec3 currentOpticalDepth,
        vec3 extinction,
        float rayDistance,
        float cosine) {
    float distance = min(
            PRIME_THIN_WALLED_THICKNESS_M / max(abs(cosine), 1.0e-3),
            rayDistance);
    return currentOpticalDepth + extinction * distance;
}

vec3 primeShadowOpticalDepth(vec3 opticalDepth) {
    // Malformed or clipped boundary sequences may leave a negative residue. They must never turn
    // absorption into energy gain.
    return max(opticalDepth, vec3(0.0));
}

#endif
