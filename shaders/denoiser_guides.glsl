#ifndef PRIME_DENOISER_GUIDES_GLSL
#define PRIME_DENOISER_GUIDES_GLSL

// Supplemental observations may spend extra rays, but they never participate in the beauty-path
// estimator. Screenshot/reference shaders intentionally do not include this file.
struct PrimeTransparencyGuideResult {
    vec3 backgroundRadiance;
    float reflectionHitDistance;
    bool valid;
};

// DLSS RR's color-before-transparency guide must remain a real transport signal rather than a
// material mask. For the first visible water/glass interface, force a transmission proposal and
// integrate the rest of that path with the resulting medium stack. This independent path exists
// only when the RR bit is set and excludes the interface reflection.
PrimeTransparencyGuideResult primeIntegrateTransparencyGuide(
        PathState cameraPath,
        IntegratorRecord integrator) {
    PrimeTransparencyGuideResult guide;
    guide.backgroundRadiance = vec3(0.0);
    guide.reflectionHitDistance = 0.0;
    guide.valid = false;

    SurfaceInteraction primary = primeTraceSurface(cameraPath.traceOrigin, cameraPath.rayDirection);
    if (primary.hitKind == PRIME_HIT_NONE
            || !primeMaterialIsTransmissive(primary.materialFlags)) {
        return guide;
    }

    vec3 viewDirection = -cameraPath.rayDirection;
    vec3 outwardNormal = primeSurfaceOutwardShadingNormal(primary);
    vec3 reflectedDirection = normalize(reflect(cameraPath.rayDirection, outwardNormal));
    SurfaceInteraction reflected = primeTraceSurface(
            primeOffsetRayOrigin(primary.position, primary.geometricNormal, reflectedDirection),
            reflectedDirection);
    guide.reflectionHitDistance = reflected.hitKind == PRIME_HIT_NONE
            ? PRIME_NRD_FP16_MAX
            : max(reflected.t, 0.0);

    PrimeRcVolumeStack volumeStack = (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
    cameraPath.sampleDimension = 1u;
    PrimeSampleBase sampleBase = primeMakeSampleBase(cameraPath, 1u);
    vec3 transmissionSample = primeSobolSample3D(
            sampleBase,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_SECONDARY);
    PrimeTransmissiveBsdfSample transmitted = primeSampleMinecraftTransmissionBranch(
            primary.baseColor,
            primeSurfaceOpacity(primary),
            outwardNormal,
            primary.materialFlags,
            primary.labPbrNormal,
            primary.labPbrSpecular,
            viewDirection,
            transmissionSample,
            false,
            primary.t,
            volumeStack);
    if (!primeValidScatter(transmitted.bsdfSample)) {
        return guide;
    }
    float rouletteSample = primeHashSample1D(
            sampleBase,
            PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
            PRIME_SAMPLE_DIMENSION_SECONDARY);
    if (!primeAdvancePath(cameraPath, primary, transmitted.bsdfSample, rouletteSample)) {
        return guide;
    }

    PrimeIntegrationResult background = primeIntegrateWithVolume(
            cameraPath, integrator, transmitted.volumeStack);
    guide.backgroundRadiance = primeResolveIntegrationRadiance(background);
    guide.valid = !any(isnan(guide.backgroundRadiance))
            && !any(isinf(guide.backgroundRadiance))
            && all(greaterThanEqual(guide.backgroundRadiance, vec3(0.0)));
    return guide;
}

#endif
