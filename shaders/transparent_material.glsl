#ifndef PRIME_TRANSPARENT_MATERIAL_GLSL
#define PRIME_TRANSPARENT_MATERIAL_GLSL

#include "color_space.glsl"

// Rec.2020's near-monochromatic primaries are 630, 532 and 467 nm. Pope and Fry's measured
// absorption coefficients for pure water at 22 C are 0.2916 m^-1 at 630 nm and, by linear
// interpolation of their Table 3, 0.04444 m^-1 at 532 nm and 0.010182 m^-1 at 467 nm.
const vec3 PRIME_PURE_WATER_ABSORPTION_M_INV = vec3(0.2916, 0.04444, 0.010182);
const float PRIME_THIN_WALLED_THICKNESS_M = 0.0625;
// Vanilla stained glass uses 102/255 for its body. The fixed decimal preserves the requested
// material calibration, while the half-UNORM threshold distinguishes any authored alpha from 0.
const float PRIME_STAINED_GLASS_ABSORPTION_OPACITY = 0.4;
const float PRIME_GLASS_ROUGH_OPACITY_THRESHOLD = 0.5;
const float PRIME_GLASS_STAINED_REFERENCE_THRESHOLD = 0.5 / 255.0;

bool primeGlassReferenceIsStained(float opacity) {
    return opacity >= PRIME_GLASS_STAINED_REFERENCE_THRESHOLD;
}

bool primeGlassTexelIsRough(float opacity) {
    return opacity > PRIME_GLASS_ROUGH_OPACITY_THRESHOLD;
}

vec3 primeShadowCanonicalExtinction(vec3 extinction) {
    // Wavefront records retain extinction as three fp16 values. Glass boundaries use the same
    // representation as a queued starting medium; water additionally uses exact integer winding.
    vec2 extinction01 = unpackHalf2x16(packHalf2x16(extinction.xy));
    float extinction2 = unpackHalf2x16(packHalf2x16(vec2(extinction.z, 0.0))).x;
    return vec3(extinction01, extinction2);
}

vec3 primeShadowWaterExtinction() {
    return primeShadowCanonicalExtinction(PRIME_PURE_WATER_ABSORPTION_M_INV);
}

bool primeShadowMediumIsWater(vec3 extinction) {
    return all(equal(
            primeShadowCanonicalExtinction(extinction),
            primeShadowWaterExtinction()));
}

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
    return primeGlassReferenceIsStained(referenceOpacity)
            ? primeGlassVolumeExtinction(
                    referenceBaseColor, PRIME_STAINED_GLASS_ABSORPTION_OPACITY)
            : vec3(0.0);
}

vec3 primeShadowBoundaryOpticalDepthMoment(
        vec3 currentMoment,
        vec3 extinction,
        float hitDistance,
        bool entering) {
    return currentMoment + extinction * (entering ? -hitDistance : hitDistance);
}

vec3 primeShadowBoundaryTerminalExtinction(
        vec3 currentExtinction,
        vec3 boundaryExtinction,
        bool entering) {
    return currentExtinction + boundaryExtinction * (entering ? 1.0 : -1.0);
}

vec3 primeShadowThinOpticalDepth(
        vec3 currentMoment,
        vec3 extinction,
        float rayDistance,
        float cosine) {
    float distance = min(
            PRIME_THIN_WALLED_THICKNESS_M / max(abs(cosine), 1.0e-3),
            rayDistance);
    return currentMoment + extinction * distance;
}

vec3 primeShadowOpticalDepth(
        vec3 opticalDepthMoment,
        vec3 terminalExtinction,
        int waterWinding,
        float rayDistance) {
    // Each boundary contributes extinction * -t on entry and extinction * t on exit. Only a
    // medium that still encloses the ray endpoint contributes the terminal tMax term. Water's
    // signed winding makes its endpoint state exact; other media use canonical fp16 extinction.
    vec3 endpointExtinction = terminalExtinction
            + primeShadowWaterExtinction() * float(waterWinding);
    vec3 opticalDepth = opticalDepthMoment + endpointExtinction * rayDistance;
    // Malformed or clipped boundary sequences may leave a negative residue. They must never turn
    // absorption into energy gain.
    return max(opticalDepth, vec3(0.0));
}

#endif
