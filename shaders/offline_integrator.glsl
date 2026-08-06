#ifndef PRIME_OFFLINE_INTEGRATOR_GLSL
#define PRIME_OFFLINE_INTEGRATOR_GLSL

struct PrimeOfflineAreaRequest {
    vec3 direction;
    float distance;
    vec3 contribution;
    bool valid;
    vec3 startingExtinction;
    uint startingMediumCount;
};

PrimeOfflineAreaRequest primeEmptyOfflineAreaRequest() {
    PrimeOfflineAreaRequest request;
    request.direction = vec3(0.0);
    request.distance = 0.0;
    request.contribution = vec3(0.0);
    request.valid = false;
    request.startingExtinction = vec3(0.0);
    request.startingMediumCount = 0u;
    return request;
}

vec3 primeEvaluateOfflineDirect(
        SurfaceInteraction surface,
        vec3 viewDirection,
        LightSample light,
        vec3 radiance,
        PrimeRcVolumeStack volumeStack) {
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, light)
            || all(lessThanEqual(radiance, vec3(0.0)))) {
        return vec3(0.0);
    }
    if (primeMaterialIsTransmissive(surface.materialFlags)) {
        BsdfEvaluation evaluation = primeEvaluateOfflineMinecraftTransmission(
                surface, viewDirection, light.direction, volumeStack);
        return primeTripleProduct(
                radiance,
                evaluation.response,
                primePowerHeuristicOverPdf(light.pdf, evaluation.pdf));
    }
    PrimeBsdfComponents components;
    if (primeMaterialIsFoliage(surface.materialFlags)) {
        components = primeEvaluateMinecraftFoliageComponents(
                surface.baseColor,
                shadingNormal,
                surface.labPbrNormal,
                surface.labPbrSpecular,
                surface.materialFlags,
                viewDirection,
                light.direction,
                0.0,
                volumeStack);
    } else {
        components = primeEvaluateOpaqueComponents(
                surface.baseColor,
                shadingNormal,
                surface.labPbrNormal,
                surface.labPbrSpecular,
                surface.materialFlags,
                viewDirection,
                light.direction,
                0.0,
                volumeStack);
    }
    float weightedInversePdf = primePowerHeuristicOverPdf(
            light.pdf, components.pdf);
    return primeTripleProduct(
            radiance,
            components.diffuseResponse + components.specularResponse,
            weightedInversePdf);
}

vec3 primeEstimateOfflineSun(
        PathState path,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        vec3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeRcVolumeStack volumeStack) {
    LightSample light = primeSampleSun(
            integrator,
            surface.position,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY));
    if (!primeAtmosphereDistantDirectionVisible(light.direction)) {
        return vec3(0.0);
    }
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, light)) {
        return vec3(0.0);
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            surface.position, surface.geometricNormal, light, volumeStack);
    if (shadow.hitDistance < PRIME_NRD_FP16_MAX) {
        return vec3(0.0);
    }
    vec3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, light) * shadow.transmittance;
    return path.throughput * primeEvaluateOfflineDirect(
            surface, viewDirection, light, radiance, volumeStack);
}

PrimeOfflineAreaRequest primePrepareOfflineArea(
        PathState path,
        SurfaceInteraction surface,
        vec3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeRcVolumeStack volumeStack) {
    PrimeOfflineAreaRequest request = primeEmptyOfflineAreaRequest();
    AreaLightSample area = primeSampleAreaLight(
            surface.position,
            primeAreaLightReceiverNormal(surface, viewDirection),
            primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_SECONDARY));
    vec3 radiance = primeResolveSampledAreaLightRadiance(area);
    vec3 contribution = path.throughput * primeEvaluateOfflineDirect(
            surface, viewDirection, area.light, radiance, volumeStack);
    request.valid = area.light.pdf > 0.0
            && area.light.distance > 0.0
            && any(greaterThan(contribution, vec3(0.0)));
    if (request.valid) {
        request.direction = area.light.direction;
        request.distance = area.light.distance;
        request.contribution = contribution;
        request.startingExtinction = volumeStack.count > 0u
                ? volumeStack.values[volumeStack.count - 1u].extinction
                : vec3(0.0);
        request.startingMediumCount = volumeStack.count;
    }
    return request;
}

PrimeShadowTrace primeTraceOfflineAreaShadow(
        vec3 physicalPosition,
        PrimeOfflineAreaRequest request,
        PrimeRcVolumeStack startingVolumeStack) {
    uint startingMediumCount = min(
            request.startingMediumCount, PRIME_RC_MAX_VOLUME_STACK_SIZE);
    vec3 startingExtinction0 = startingMediumCount > 0u
            ? primeShadowCanonicalExtinction(
                    startingVolumeStack.values[0].extinction)
            : vec3(0.0);
    vec3 startingExtinction1 = startingMediumCount > 1u
            ? primeShadowCanonicalExtinction(
                    startingVolumeStack.values[1].extinction)
            : vec3(0.0);
    primeShadowPayload.opticalDepthMomentHitDistance = vec4(0.0);
    primeShadowPayload.terminalExtinctionRayDistance = vec4(
            vec3(0.0),
            request.distance);
    primeShadowPayload.startingExtinction0Winding = vec4(
            startingExtinction0, startingMediumCount > 0u ? 1.0 : 0.0);
    primeShadowPayload.startingExtinction1Winding = vec4(
            startingExtinction1, startingMediumCount > 1u ? 1.0 : 0.0);
    primeShadowPayload.startingMediumCount = startingMediumCount;
    traceRayEXT(
            primeScene,
            gl_RayFlagsNoneEXT,
            0xff,
            3,
            1,
            1,
            physicalPosition + request.direction * 0.001,
            0.001,
            request.direction,
            request.distance,
            1);
    PrimeShadowTrace result;
    result.transmittance = clamp(exp(-primeShadowOpticalDepth(
                    primeShadowPayload.opticalDepthMomentHitDistance.xyz,
                    primeShadowPayload.terminalExtinctionRayDistance.xyz,
                    primeShadowPayload.startingExtinction0Winding,
                    primeShadowPayload.startingExtinction1Winding,
                    primeShadowPayload.startingMediumCount,
                    primeShadowPayload.terminalExtinctionRayDistance.w)),
            vec3(0.0),
            vec3(1.0));
    result.hitDistance = primeNrdSanitizeHitDistance(
            primeShadowPayload.opticalDepthMomentHitDistance.w);
    return result;
}

PrimePathScatter primeSampleOfflinePathSurface(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 sampleValue,
        PrimeRcVolumeStack volumeStack) {
    PrimePathScatter result;
    result.volumeStack = volumeStack;
    if (primeMaterialIsFoliage(surface.materialFlags)) {
        PrimeRcState state = primeMinecraftFoliageState(
                surface.baseColor,
                primeSurfaceShadingNormal(surface, viewDirection),
                surface.labPbrNormal,
                surface.labPbrSpecular,
                surface.materialFlags,
                viewDirection,
                surface.t,
                volumeStack);
        result.bsdf = primeSampleMinecraftFoliageFromState(
                state, viewDirection, sampleValue, volumeStack);
    } else if (primeMaterialIsTransmissive(surface.materialFlags)) {
        PrimeRcState state = primeMinecraftTransmissionState(
                surface.baseColor,
                primeSurfaceOpacity(surface),
                primeSurfaceOutwardShadingNormal(surface),
                surface.materialFlags,
                surface.labPbrNormal,
                surface.labPbrSpecular,
                viewDirection,
                surface.t,
                volumeStack);
        PrimeTransmissiveBsdfSample sampled =
                primeSampleOfflineMinecraftTransmissionFromState(
                        state,
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        surface.materialFlags,
                        viewDirection,
                        sampleValue,
                        volumeStack);
        result.bsdf = sampled.bsdfSample;
        result.volumeStack = sampled.volumeStack;
    } else {
        vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
        PrimeRcState state = primeOpaqueState(
                surface.baseColor,
                shadingNormal,
                surface.labPbrNormal,
                surface.labPbrSpecular,
                surface.materialFlags,
                viewDirection,
                surface.t,
                volumeStack);
        result.bsdf = primeSampleOpaqueFromState(
                state, shadingNormal, viewDirection, sampleValue, volumeStack);
    }
    return result;
}

bool primeAdvanceOfflinePath(
        inout PathState path,
        inout float etaScale,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        PrimePreparedSampleBase bounceSample) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_PATH_ADVANCE, path.bounce);
    vec3 nextThroughput = primeProductOver(path.throughput, bsdf.response, bsdf.pdf);
    primeRecordNonnegative(nextThroughput);
    if (all(equal(nextThroughput, vec3(0.0)))) {
        return false;
    }
    path.throughput = nextThroughput;
    etaScale = primeOfflineEtaScaleAfterScatter(
            etaScale,
            bsdf.relativeEta,
            (bsdf.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u);
    primeRecordNonnegative(etaScale);
    path.physicalOrigin = surface.position;
    path.traceOrigin = primeOffsetRayOrigin(
            path.physicalOrigin, surface.geometricNormal, bsdf.direction);
    path.rayDirection = bsdf.direction;
    path.previousBsdfPdf = bsdf.pdf;
    path.flags = (bsdf.eventFlags & PRIME_BSDF_EVENT_DELTA) != 0u
            ? PRIME_PATH_PREVIOUS_DELTA
            : 0u;

    uint rrDepth = path.rrDepth++;
    if (rrDepth < PRIME_RUSSIAN_ROULETTE_START) {
        return true;
    }
    float survival = primeOfflineRussianRouletteSurvival(
            path.throughput, etaScale);
    primeRecordUnit(survival);
    float rouletteSample = primeHashSample1D(
            bounceSample,
            PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    if (rouletteSample >= survival) {
        return false;
    }
    path.throughput = primeRussianRouletteReweight(path.throughput, survival);
    primeRecordNonnegative(path.throughput);
    return true;
}

bool primeIntegrateOfflineSurface(
        inout PathState path,
        inout float etaScale,
        IntegratorRecord integrator,
        inout PrimeRcVolumeStack volumeStack,
        inout vec3 radiance,
        inout float primaryDistance,
        inout vec3 primaryAlbedo,
        inout uint primaryMaterialFlags,
        SurfaceInteraction surface,
        bool deferArea,
        out PrimeOfflineAreaRequest areaRequest) {
    areaRequest = primeEmptyOfflineAreaRequest();
    if (!primeKnownHitKind(surface)) {
        return false;
    }
    if (surface.hitKind == PRIME_HIT_NONE) {
        primeAccumulate(radiance, primeEvaluateEnvironmentContribution(path, integrator));
        return false;
    }
    if (!primeApplySegmentMedium(path, surface, volumeStack)) {
        return false;
    }

    bool previousUsedAreaNee =
            (path.flags & PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE) != 0u;
    primeAccumulate(
            radiance,
            primeEvaluateHitEmission(path, surface, previousUsedAreaNee));
    vec3 viewDirection = -path.rayDirection;
    if (!(primaryDistance >= 0.0)) {
        primaryDistance = length(surface.position - primePush.cameraPosition);
        primaryAlbedo = surface.baseColor;
        primaryMaterialFlags = surface.materialFlags;
    }

    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    bool neeEligible = primeOfflineHasNonDeltaLobe(
            surface.materialFlags,
            surface.baseColor,
            primeSurfaceLinearRoughness(surface));
    if (neeEligible) {
        primeAccumulate(
                radiance,
                primeEstimateOfflineSun(
                        path,
                        integrator,
                        surface,
                        viewDirection,
                        preparedSample,
                        volumeStack));
        areaRequest = primePrepareOfflineArea(
                path,
                surface,
                viewDirection,
                preparedSample,
                volumeStack);
    }
    // Reverse MIS depends on whether the competing light-sampling technique existed at this
    // vertex, not on whether this particular random Area sample produced a non-zero request.
    bool usedAreaNee = neeEligible;
    path.previousLightNormal = usedAreaNee
            ? primeAreaLightReceiverNormal(surface, viewDirection)
            : 0u;
    if (areaRequest.valid && !deferArea) {
        PrimeShadowTrace shadow = primeTraceShadow(
                surface.position,
                surface.geometricNormal,
                LightSample(
                        areaRequest.direction,
                        areaRequest.distance,
                        vec3(0.0),
                        1.0,
                        0u),
                volumeStack);
        if (shadow.hitDistance >= PRIME_NRD_FP16_MAX) {
            primeAccumulate(
                    radiance,
                    areaRequest.contribution * shadow.transmittance);
        }
        areaRequest = primeEmptyOfflineAreaRequest();
    }

    PrimePathScatter scatter = primeSampleOfflinePathSurface(
            surface,
            viewDirection,
            primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            volumeStack);
    BsdfSample bsdf = scatter.bsdf;
    volumeStack = scatter.volumeStack;
    if (!primeHasScatter(bsdf)) {
        path.physicalOrigin = surface.position;
        path.flags = usedAreaNee ? PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE : 0u;
        return false;
    }
    if (!primeAdvanceOfflinePath(
            path, etaScale, surface, bsdf, preparedSample)) {
        path.flags = usedAreaNee ? PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE : 0u;
        return false;
    }
    path.flags |= usedAreaNee ? PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE : 0u;
    path.bounce++;
    return true;
}

#endif
