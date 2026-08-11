#language slang 2026
module "core/atmosphere_rt.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "core/atmosphere.slang";
import "core/atmosphere_epipolar.slang";
import "core/render_config.slang";


public float3 primeAtmosphereSky(float3 direction, float3 sunDirection) {
    uint width;
    uint height;
    primeSkyView.GetDimensions(width, height);
    float2 dimensions = float2(width, height);
    float2 uv = atmSkyUvFromDirection(
            direction, sunDirection, dimensions, primePush.atmosphereEyeRadiusKm);
    return max(atmSample2D(primeSkyView, uv).rgb, float3(0.0));
}

public bool primeAtmosphereDistantDirectionVisible(float3 direction) {
    return atmDistantDirectionVisible(
            primePush.atmosphereEyeRadiusKm, direction);
}

public float3 primeAtmosphereDistantTransmittance(
        float3 surfacePosition,
        float3 direction) {
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
        return float3(0.0);
    }
    float normalizedAltitude = (radius - ATM_BOTTOM_RADIUS_KM) / ATM_THICKNESS_KM;
    float2 uv = atmTransmittanceUv(direction.y, normalizedAltitude);
    return atmRec2020Transmittance(
            atmSample2D(primeTransmittanceLow, uv),
            atmSample2D(primeTransmittanceHigh, uv));
}

public float3 primeAtmosphereSunTransmittance(
        float3 surfacePosition,
        float3 directionToSun) {
    return primeAtmosphereDistantTransmittance(surfacePosition, directionToSun);
}

public float3 primeApplyAerialPerspective(
        uint2 pixel,
        float2 cameraSample,
        float primaryDistance,
        float3 radiance) {
    if (isnan(primaryDistance) || isinf(primaryDistance) || primaryDistance < 0.0) {
        return radiance;
    }
    float distanceKm = primaryDistance * ATM_WORLD_UNIT_SCALE_KM;
    uint aerialWidth;
    uint aerialHeight;
    uint aerialDepth;
    primeAerialRadiance.GetDimensions(
            aerialWidth, aerialHeight, aerialDepth);
    int3 aerialDimensions = int3(aerialWidth, aerialHeight, aerialDepth);
    float firstSliceDepth = 0.5 / float(max(aerialDimensions.z, 1));
    float firstSliceDistanceKm = ATM_AERIAL_MAX_DISTANCE_KM
            * firstSliceDepth * firstSliceDepth;
    float normalizedDepth = max(firstSliceDepth, sqrt(clamp(
            distanceKm / max(ATM_AERIAL_MAX_DISTANCE_KM, 1.0e-6), 0.0, 1.0)));
    float nearWeight = clamp(
            distanceKm / max(firstSliceDistanceKm, 1.0e-6), 0.0, 1.0);
    // Offline accumulation integrates the pixel filter. Sampling this direction-dependent
    // term at pixel centre would leave a deterministic sub-pixel bias in the reference image.
    float2 screenUv = (float2(pixel) + cameraSample) / float2(primePush.outputExtent);
    float3 uvw = float3(screenUv, normalizedDepth);
    float2 epipoleNdc = atmEpipolarProjectDirection(
            primePush.inverseViewProjection,
            primePush.sunDirection);
    float3 inscatter = max(
            atmSampleEpipolar(
                    primeAerialRadiance,
                    screenUv,
                    normalizedDepth,
                    epipoleNdc).rgb,
            float3(0.0));
    float3 transmittance = clamp(
            atmSample3D(primeAerialTransmittance, uvw).rgb,
            float3(0.0),
            float3(1.0));
    // The LUT has centre-sampled Z slices and therefore has no stored zero-distance value.
    // Blend the first slice back to the exact identity boundary instead of applying a finite fog
    // segment to nearby geometry. Invalid LUT components must also be neutral here: propagating a
    // NaN reaches raygen's last-resort guard and turns the complete pixel black.
    bool3 invalidInscatter = bool3(
            isnan(inscatter.x) || isinf(inscatter.x),
            isnan(inscatter.y) || isinf(inscatter.y),
            isnan(inscatter.z) || isinf(inscatter.z));
    inscatter = select(invalidInscatter, float3(0.0), inscatter);
    bool3 invalidTransmittance = bool3(
            isnan(transmittance.x) || isinf(transmittance.x),
            isnan(transmittance.y) || isinf(transmittance.y),
            isnan(transmittance.z) || isinf(transmittance.z));
    transmittance = select(
            invalidTransmittance,
            float3(1.0),
            transmittance);
    // Atmosphere LUT construction is linear in the extraterrestrial source. Match sky and direct
    // sun evaluation by applying the same runtime EV scale before this deterministic term enters
    // an offline sample; otherwise non-zero sun EV would change terrain and sky but leave
    // aerial in-scattering at the calibrated base intensity.
    inscatter *= nearWeight * primeSunRadianceMultiplier();
    transmittance = lerp(float3(1.0), transmittance, nearWeight);
    return radiance * transmittance + inscatter;
}
