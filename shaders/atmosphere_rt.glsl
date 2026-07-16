#ifndef PRIME_ATMOSPHERE_RT_GLSL
#define PRIME_ATMOSPHERE_RT_GLSL

#include "atmosphere.glsl"

ATM_DECLARE_SAMPLE_2D(primeSampleSkyView, primeSkyView)
ATM_DECLARE_SAMPLE_2D(primeSampleTransmittanceLow, primeTransmittanceLow)
ATM_DECLARE_SAMPLE_2D(primeSampleTransmittanceHigh, primeTransmittanceHigh)
ATM_DECLARE_SAMPLE_3D(primeSampleAerialRadiance, primeAerialRadiance)
ATM_DECLARE_SAMPLE_3D(primeSampleAerialTransmittance, primeAerialTransmittance)

vec3 primeAtmosphereSky(vec3 direction, vec3 sunDirection) {
    vec2 dimensions = vec2(imageSize(primeSkyView));
    vec2 uv = atmSkyUvFromDirection(
            direction, sunDirection, dimensions, primePush.atmosphereEyeRadiusKm);
    return max(primeSampleSkyView(uv).rgb, vec3(0.0));
}

vec3 primeAtmosphereSunTransmittance(
        vec3 surfacePosition,
        vec3 directionToSun) {
    // The same artistic scale maps both vertical coordinates and travelled distance: one
    // Minecraft block represents 0.001 atmospheric kilometres. Splitting these scales would make
    // density layers disagree with aerial perspective and break the atmosphere/terrain contract.
    float radius = primePush.atmosphereEyeRadiusKm
            + (surfacePosition.y - primePush.cameraPosition.y) * ATM_WORLD_UNIT_SCALE_KM;
    radius = clamp(radius, ATM_BOTTOM_RADIUS_KM + 0.001, ATM_TOP_RADIUS_KM - 0.001);
    // Horizon visibility is a property of the current atmospheric view, not of every terrain
    // sample independently. Testing at surface radius makes lower terrain enter the planet shadow
    // while an elevated camera still sees the sun, producing a hard altitude boundary where only
    // direct light vanishes. Surface radius remains correct for the transmittance lookup below.
    vec3 horizonPosition = vec3(0.0, primePush.atmosphereEyeRadiusKm, 0.0);
    if (atmRaySegment(horizonPosition
            - vec3(0.0, ATM_PLANET_RADIUS_OFFSET_KM, 0.0),
            directionToSun).hitsGround) {
        return vec3(0.0);
    }
    float normalizedAltitude = (radius - ATM_BOTTOM_RADIUS_KM) / ATM_THICKNESS_KM;
    vec2 uv = atmTransmittanceUv(directionToSun.y, normalizedAltitude);
    return atmRec2020Transmittance(
            primeSampleTransmittanceLow(uv),
            primeSampleTransmittanceHigh(uv));
}

void primeApplyAerialPerspective(
        uvec2 pixel,
        float primaryDistance,
        inout vec3 radiance) {
    if (primaryDistance < 0.0) {
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
    vec2 screenUv = (vec2(pixel) + vec2(0.5)) / vec2(primePush.outputExtent);
    vec3 uvw = vec3(screenUv, normalizedDepth);
    vec3 inscatter = max(primeSampleAerialRadiance(uvw).rgb, vec3(0.0));
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
    inscatter *= nearWeight;
    transmittance = mix(vec3(1.0), transmittance, nearWeight);
    radiance = radiance * transmittance + inscatter;
}

#endif
