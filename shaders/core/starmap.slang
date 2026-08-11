#ifndef PRIME_STARMAP_SLANG
#define PRIME_STARMAP_SLANG

#include "core/celestial.slang"
#include "core/atmosphere_rt.slang"
#include "core/color_space.slang"

static const float PRIME_STARMAP_PI = 3.141592653589793;

// NASA's plate-carree source is ICRF/J2000, centred on RA 0h with RA increasing left.
float2 primeStarmapUv(IntegratorRecord integrator, float3 direction) {
    PrimeCelestialFrame frame = primeCelestialFrame(
            integrator.sunDirectionIntensity.xyz,
            primeObserverLatitudeRadians(),
            primeSolarLongitudeRadians());
    float2 equatorial =
            primeCelestialEquatorialCoordinates(frame, direction);
    return float2(
            frac(0.5 - equatorial.x / (2.0 * PRIME_STARMAP_PI)),
            clamp(0.5 - equatorial.y / PRIME_STARMAP_PI, 0.0, 1.0));
}

float3 primeStarmapRadianceUv(
        float3 surfacePosition,
        float3 direction,
        float2 uv,
        float scale) {
    float3 transmittance = primeAtmosphereDistantTransmittance(
            surfacePosition, direction);
    if (all(transmittance <= float3(0.0))) {
        return float3(0.0);
    }
    float3 source = max(primeStarmap.SampleLevel(uv, 0.0).rgb, float3(0.0));
    return primeLinearSrgbToLinearRec2020(source) * scale * transmittance;
}

float3 primeStarmapRadiance(
        IntegratorRecord integrator,
        float3 surfacePosition,
        float3 direction) {
    float scale =
            PRIME_STARMAP_BASE_RADIANCE_SCALE * primeStarRadianceMultiplier();
    return scale > 0.0
            ? primeStarmapRadianceUv(
                    surfacePosition,
                    direction,
                    primeStarmapUv(integrator, direction),
                    scale)
            : float3(0.0);
}

#endif
