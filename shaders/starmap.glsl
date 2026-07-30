#ifndef PRIME_STARMAP_GLSL
#define PRIME_STARMAP_GLSL

#include "celestial.glsl"

// NASA's plate-carree source is ICRF/J2000, centred on RA 0h with RA increasing left.
vec2 primeStarmapUv(IntegratorRecord integrator, vec3 direction) {
    PrimeCelestialFrame frame = primeCelestialFrame(
            integrator.sunDirectionIntensity.xyz,
            primeObserverLatitudeRadians(),
            primeSolarLongitudeRadians());
    vec2 equatorial =
            primeCelestialEquatorialCoordinates(frame, direction);
    return vec2(
            fract(0.5 - equatorial.x / (2.0 * PRIME_PI)),
            clamp(0.5 - equatorial.y / PRIME_PI, 0.0, 1.0));
}

vec3 primeStarmapRadianceUv(
        vec3 surfacePosition,
        vec3 direction,
        vec2 uv,
        float scale) {
    vec3 transmittance = primeAtmosphereDistantTransmittance(
            surfacePosition, direction);
    if (all(lessThanEqual(transmittance, vec3(0.0)))) {
        return vec3(0.0);
    }
    vec3 source = max(textureLod(primeStarmap, uv, 0.0).rgb, vec3(0.0));
    return primeLinearSrgbToLinearRec2020(source) * scale * transmittance;
}

vec3 primeStarmapRadiance(
        IntegratorRecord integrator,
        vec3 surfacePosition,
        vec3 direction) {
    float scale =
            PRIME_STARMAP_BASE_RADIANCE_SCALE * primeStarRadianceMultiplier();
    return scale > 0.0
            ? primeStarmapRadianceUv(
                    surfacePosition,
                    direction,
                    primeStarmapUv(integrator, direction),
                    scale)
            : vec3(0.0);
}

#endif
