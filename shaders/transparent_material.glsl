#ifndef PRIME_TRANSPARENT_MATERIAL_GLSL
#define PRIME_TRANSPARENT_MATERIAL_GLSL

// Rec.2020's near-monochromatic primaries are 630, 532 and 467 nm. Pope and Fry's measured
// absorption coefficients for pure water at 22 C are 0.2916 m^-1 at 630 nm and, by linear
// interpolation of their Table 3, 0.04444 m^-1 at 532 nm and 0.010182 m^-1 at 467 nm.
const vec3 PRIME_PURE_WATER_ABSORPTION_M_INV = vec3(0.2916, 0.04444, 0.010182);

vec3 primeGlassSurfaceTransmittance(vec3 baseColor, float opacity) {
    return mix(
            vec3(1.0),
            clamp(baseColor, vec3(0.0), vec3(1.0)),
            clamp(opacity, 0.0, 1.0));
}

float primeShadowWaterBoundaryDistance(
        float currentDistance,
        float rayDistance,
        float hitDistance,
        bool entering) {
    float remaining = max(rayDistance - hitDistance, 0.0);
    return currentDistance + (entering ? remaining : -remaining);
}

float primeShadowWaterDistance(float distance, float rayDistance) {
    return clamp(distance, 0.0, max(rayDistance, 0.0));
}

#endif
