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

bool primeGlassTexelIsRough(float opacity, bool seamlessGlass) {
    return !seamlessGlass && opacity > PRIME_GLASS_ROUGH_OPACITY_THRESHOLD;
}

vec3 primeShadowCanonicalExtinction(vec3 extinction) {
    // Wavefront records retain extinction as three fp16 values. Glass boundaries use the same
    // representation as a queued starting medium; water additionally uses exact integer winding.
    vec2 extinction01 = unpackHalf2x16(packHalf2x16(extinction.xy));
    float extinction2 = unpackHalf2x16(packHalf2x16(vec2(extinction.z, 0.0))).x;
    return vec3(extinction01, extinction2);
}

bool primeShadowSameMedium(vec3 firstExtinction, vec3 secondExtinction) {
    // A path carries fp16 extinction reconstructed from the interface it crossed, while another
    // face of the same voxel may reconstruct a neighbouring fp16 value. One fp16-scale tolerance
    // identifies that shared medium without merging the substantially different stained palette.
    vec3 scale = max(
            max(abs(firstExtinction), abs(secondExtinction)),
            vec3(1.0));
    return all(lessThanEqual(
            abs(firstExtinction - secondExtinction),
            scale * (1.0 / 1024.0)));
}

vec3 primeShadowStartingMediumCoefficient(
        vec3 outerExtinction,
        vec3 innerExtinction,
        uint mediumIndex) {
    // Nested media replace rather than add to their parent. Expressing the stack as E0 and
    // E1-E0 makes each boundary an order-independent additive moment while reconstructing
    // E1 inside the inner medium, E0 after its exit, and zero after the outer exit.
    return mediumIndex == 0u
            ? outerExtinction
            : innerExtinction - outerExtinction;
}

int primeShadowStartingMediumIndex(
        vec3 boundaryExtinction,
        vec4 outerExtinctionWinding,
        vec4 innerExtinctionWinding,
        uint mediumCount,
        bool entering) {
    bool matchesOuter = mediumCount > 0u
            && primeShadowSameMedium(
                    boundaryExtinction, outerExtinctionWinding.xyz);
    bool matchesInner = mediumCount > 1u
            && primeShadowSameMedium(
                    boundaryExtinction, innerExtinctionWinding.xyz);
    if (matchesOuter && matchesInner) {
        // Equal nested media have a zero inner difference coefficient. Prefer the deepest active
        // layer on exit and the shallowest inactive layer on entry to preserve stack topology.
        if (entering) {
            return outerExtinctionWinding.w <= 0.0 ? 0 : 1;
        }
        return innerExtinctionWinding.w > 0.0 ? 1 : 0;
    }
    return matchesInner ? 1 : (matchesOuter ? 0 : -1);
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
        vec4 outerExtinctionWinding,
        vec4 innerExtinctionWinding,
        uint startingMediumCount,
        float rayDistance) {
    // Each boundary contributes extinction * -t on entry and extinction * t on exit. Only a
    // medium that still encloses the ray endpoint contributes the terminal tMax term. The ray's
    // starting stack uses path-provided extinction and exact integer-valued windings. The inner
    // difference coefficient restores the outer medium instead of adding both absorptions.
    vec3 endpointExtinction = terminalExtinction;
    if (startingMediumCount > 1u) {
        // Evaluate the layered endpoint directly. When both windings are one this returns the
        // inner extinction without an E0 + (E1-E0) cancellation before multiplication by tMax.
        endpointExtinction += innerExtinctionWinding.xyz
                * innerExtinctionWinding.w;
        endpointExtinction += outerExtinctionWinding.xyz
                * (outerExtinctionWinding.w - innerExtinctionWinding.w);
    } else if (startingMediumCount > 0u) {
        endpointExtinction += outerExtinctionWinding.xyz
                * outerExtinctionWinding.w;
    }
    vec3 opticalDepth = opticalDepthMoment + endpointExtinction * rayDistance;
    // Malformed or clipped boundary sequences may leave a negative residue. They must never turn
    // absorption into energy gain.
    return max(opticalDepth, vec3(0.0));
}

#endif
