#ifndef PRIME_DENOISER_GUIDES_GLSL
#define PRIME_DENOISER_GUIDES_GLSL

// Supplemental observations may spend extra rays, but they never participate in the beauty-path
// estimator. Screenshot/reference shaders intentionally do not include this file.
struct PrimeTransparencyGuideResult {
    vec3 backgroundRadiance;
    float reflectionHitDistance;
    bool valid;
};

float primeTraceReflectionGuideFromPrimary(
        PathState cameraPath,
        PrimeDenoiserGuides primaryGuides) {
    vec3 normal = normalize(primaryGuides.primaryNormal);
    vec3 position = primePush.cameraPosition + primaryGuides.primaryPosition;
    vec3 reflectedDirection = normalize(reflect(cameraPath.rayDirection, normal));
    // Prime currently submits the same surface normal used by the transmissive BSDF; tangent
    // normal mapping remains disabled. When those normals diverge, guides must additionally carry
    // the packed geometric normal so this traversal offset does not regress to a shading offset.
    SurfaceInteraction reflected = primeTraceSurface(
            primeOffsetRayOrigin(position, normal, reflectedDirection),
            reflectedDirection);
    return reflected.hitKind == PRIME_HIT_NONE
            ? PRIME_NRD_FP16_MAX
            : max(reflected.t, 0.0);
}

bool primeValidTransparencyRadiance(vec3 radiance) {
    return !any(isnan(radiance))
            && !any(isinf(radiance))
            && all(greaterThanEqual(radiance, vec3(0.0)));
}

// DLSS RR's color-before-transparency guide must remain a real transport signal rather than a
// material mask. A closed smooth dielectric uses an explicit Fresnel branch proposal in Prime's
// complete BSDF sampler. If the beauty path selected transmission, its entire continuation is
// already the desired conditional sample: multiplying by the recorded proposal probability
// exactly restores the physical transmission weight divided out for unbiased branch selection.
// Only the missing reflection hit-distance ray is traced in that common case. Reflection samples
// and closures without that exact proposal contract retain the independent forced-transmission
// fallback below.
PrimeTransparencyGuideResult primeIntegrateTransparencyGuide(
        PathState cameraPath,
        IntegratorRecord integrator,
        PrimeIntegrationResult beauty) {
    PrimeTransparencyGuideResult guide;
    guide.backgroundRadiance = vec3(0.0);
    guide.reflectionHitDistance = 0.0;
    guide.valid = false;

    uint materialFlags = beauty.guides.primaryMaterialFlags;
    uint eventFlags = beauty.guides.primaryScatterEventFlags;
    bool closedSmoothInterface = beauty.guides.primaryLinearRoughness == 0.0
            && (materialFlags & PRIME_MATERIAL_FLAG_THIN_WALLED) == 0u;
    bool selectedTransmission = closedSmoothInterface
            && (eventFlags & (PRIME_BSDF_EVENT_TRANSMISSION | PRIME_BSDF_EVENT_DELTA))
                    == (PRIME_BSDF_EVENT_TRANSMISSION | PRIME_BSDF_EVENT_DELTA)
            && (eventFlags & PRIME_BSDF_EVENT_REFLECTION) == 0u;
    if (selectedTransmission) {
        float proposalProbability = beauty.guides.primaryScatterProposalProbability;
        guide.backgroundRadiance = beauty.radiance.specular * proposalProbability;
        guide.reflectionHitDistance = primeTraceReflectionGuideFromPrimary(
                cameraPath, beauty.guides);
        guide.valid = proposalProbability > 0.0
                && proposalProbability <= 1.0
                && !isnan(proposalProbability)
                && !isinf(proposalProbability)
                && primeValidTransparencyRadiance(guide.backgroundRadiance);
        return guide;
    }

    SurfaceInteraction primary = primeTraceSurface(cameraPath.traceOrigin, cameraPath.rayDirection);
    if (primary.hitKind == PRIME_HIT_NONE
            || !primeMaterialIsTransmissive(primary.materialFlags)) {
        return guide;
    }

    vec3 viewDirection = -cameraPath.rayDirection;
    vec3 outwardNormal = primeSurfaceOutwardShadingNormal(primary);
    bool selectedReflection = closedSmoothInterface
            && (eventFlags & (PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_DELTA))
                    == (PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_DELTA)
            && (eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) == 0u;
    if (selectedReflection && beauty.guides.specularHitDistance > 0.0) {
        guide.reflectionHitDistance = beauty.guides.specularHitDistance;
    } else {
        vec3 reflectedDirection = normalize(reflect(cameraPath.rayDirection, outwardNormal));
        SurfaceInteraction reflected = primeTraceSurface(
                primeOffsetRayOrigin(
                        primary.position, primary.geometricNormal, reflectedDirection),
                reflectedDirection);
        guide.reflectionHitDistance = reflected.hitKind == PRIME_HIT_NONE
                ? PRIME_NRD_FP16_MAX
                : max(reflected.t, 0.0);
    }

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
    guide.valid = primeValidTransparencyRadiance(guide.backgroundRadiance);
    return guide;
}

#endif
