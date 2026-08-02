#ifndef PRIME_ATMOSPHERE_RT_GLSL
#define PRIME_ATMOSPHERE_RT_GLSL

#include "atmosphere.glsl"
#include "atmosphere_epipolar.glsl"

ATM_DECLARE_SAMPLE_2D(primeSampleSkyView, primeSkyView)
ATM_DECLARE_SAMPLE_2D(primeSampleTransmittanceLow, primeTransmittanceLow)
ATM_DECLARE_SAMPLE_2D(primeSampleTransmittanceHigh, primeTransmittanceHigh)
ATM_DECLARE_SAMPLE_EPIPOLAR(primeSampleAerialRadiance, primeAerialRadiance)
ATM_DECLARE_SAMPLE_3D(primeSampleAerialTransmittance, primeAerialTransmittance)

vec3 primeAtmosphereSky(vec3 direction, vec3 sunDirection) {
    vec2 dimensions = vec2(imageSize(primeSkyView));
    vec2 uv = atmSkyUvFromDirection(
            direction, sunDirection, dimensions, primePush.atmosphereEyeRadiusKm);
    return max(primeSampleSkyView(uv).rgb, vec3(0.0));
}

bool primeAtmosphereDistantDirectionVisible(vec3 direction) {
    return atmDistantDirectionVisible(
            primePush.atmosphereEyeRadiusKm, direction);
}

vec3 primeAtmosphereDistantTransmittance(
        vec3 surfacePosition,
        vec3 direction) {
    // The same internal scale maps vertical coordinates and travelled distance. Splitting them
    // would make density layers disagree with aerial perspective.
    float radius = primePush.atmosphereEyeRadiusKm
            + (surfacePosition.y - primePush.cameraPosition.y) * ATM_WORLD_UNIT_SCALE_KM;
    radius = clamp(
            radius,
            ATM_BOTTOM_RADIUS_KM + ATM_WORLD_UNIT_SCALE_KM,
            ATM_TOP_RADIUS_KM - ATM_WORLD_UNIT_SCALE_KM);
    // Horizon visibility is a property of the current atmospheric view, not of every terrain
    // sample independently. Testing at surface radius makes lower terrain enter the planet shadow
    // while an elevated camera still sees the sun, producing a hard altitude boundary where only
    // direct light vanishes. Surface radius remains correct for the transmittance lookup below.
    if (!primeAtmosphereDistantDirectionVisible(direction)) {
        return vec3(0.0);
    }
    float normalizedAltitude = (radius - ATM_BOTTOM_RADIUS_KM) / ATM_THICKNESS_KM;
    vec2 uv = atmTransmittanceUv(direction.y, normalizedAltitude);
    return atmRec2020Transmittance(
            primeSampleTransmittanceLow(uv),
            primeSampleTransmittanceHigh(uv));
}

vec3 primeAtmosphereSunTransmittance(
        vec3 surfacePosition,
        vec3 directionToSun) {
    return primeAtmosphereDistantTransmittance(surfacePosition, directionToSun);
}

void primeApplyAerialPerspective(
        uvec2 pixel,
        vec2 cameraSample,
        float primaryDistance,
        inout vec3 radiance) {
    if (isnan(primaryDistance) || isinf(primaryDistance) || primaryDistance < 0.0) {
        return;
    }
    float distanceKm = primaryDistance * ATM_WORLD_UNIT_SCALE_KM;
    ivec3 aerialDimensions = imageSize(primeAerialRadiance);
    float firstSliceDepth = 0.5 / float(max(aerialDimensions.z, 1));
    float firstSliceDistanceKm = ATM_AERIAL_MAX_DISTANCE_KM
            * firstSliceDepth * firstSliceDepth;
    float normalizedDepth = max(firstSliceDepth, sqrt(clamp(
            distanceKm / max(ATM_AERIAL_MAX_DISTANCE_KM, 1.0e-6), 0.0, 1.0)));
    float nearWeight = clamp(
            distanceKm / max(firstSliceDistanceKm, 1.0e-6), 0.0, 1.0);
    // Offline accumulation integrates the pixel filter. Sampling this direction-dependent
    // term at pixel centre would leave a deterministic sub-pixel bias in the reference image.
    vec2 screenUv = (vec2(pixel) + cameraSample) / vec2(primePush.outputExtent);
    vec3 uvw = vec3(screenUv, normalizedDepth);
    vec2 epipoleNdc = atmEpipolarProjectDirection(
            primePush.inverseViewProjection,
            primePush.sunDirection);
    vec3 inscatter = max(
            primeSampleAerialRadiance(
                    screenUv,
                    normalizedDepth,
                    epipoleNdc).rgb,
            vec3(0.0));
    vec3 transmittance = clamp(
            primeSampleAerialTransmittance(uvw).rgb, vec3(0.0), vec3(1.0));
    // The LUT has centre-sampled Z slices and therefore has no stored zero-distance value.
    // Blend the first slice back to the exact identity boundary instead of applying a finite fog
    // segment to nearby geometry. Invalid LUT components must also be neutral here: propagating a
    // NaN reaches raygen's last-resort guard and turns the complete pixel black.
    bvec3 invalidInscatter = bvec3(
            isnan(inscatter.x) || isinf(inscatter.x),
            isnan(inscatter.y) || isinf(inscatter.y),
            isnan(inscatter.z) || isinf(inscatter.z));
    inscatter = mix(inscatter, vec3(0.0), invalidInscatter);
    bvec3 invalidTransmittance = bvec3(
            isnan(transmittance.x) || isinf(transmittance.x),
            isnan(transmittance.y) || isinf(transmittance.y),
            isnan(transmittance.z) || isinf(transmittance.z));
    transmittance = mix(
            transmittance,
            vec3(1.0),
            invalidTransmittance);
    // Atmosphere LUT construction is linear in the extraterrestrial source. Match sky and direct
    // sun evaluation by applying the same runtime EV scale before this deterministic term enters
    // an offline sample; otherwise non-zero sun EV would change terrain and sky but leave
    // aerial in-scattering at the calibrated base intensity.
    inscatter *= nearWeight * primeSunRadianceMultiplier();
    transmittance = mix(vec3(1.0), transmittance, nearWeight);
    radiance = radiance * transmittance + inscatter;
}

#endif
