#ifndef PRIME_OFFLINE_INTEGRATOR_SLANG
#define PRIME_OFFLINE_INTEGRATOR_SLANG

struct PrimeOfflineAreaRequest {
    float3 direction;
    float distance;
    float3 contribution;
    bool valid;
    float3 startingExtinction;
    uint startingMediumCount;
};

struct PrimeOfflineSurfaceStep {
    PrimeOfflineAreaRequest areaRequest;
    bool continuation;
};

PrimeOfflineAreaRequest primeEmptyOfflineAreaRequest() {
    PrimeOfflineAreaRequest request;
    request.direction = float3(0.0);
    request.distance = 0.0;
    request.contribution = float3(0.0);
    request.valid = false;
    request.startingExtinction = float3(0.0);
    request.startingMediumCount = 0u;
    return request;
}

float3 primeEvaluateOfflineDirect(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        LightSample light,
        float3 radiance,
        PrimeOpenPbrVolumeStack volumeStack) {
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, light)
            || all(lessThanEqual(radiance, float3(0.0)))) {
        return float3(0.0);
    }
    PrimeNumericalObservation observation = primeBsdfObservationCreate();
    if (primeMaterialIsTransmissive(surface.materialControl)) {
        BsdfEvaluation evaluation = primeEvaluateOfflineMinecraftTransmission(
                surface,
                viewDirection,
                light.direction,
                volumeStack,
                observation);
        float3 result = primeTripleProduct(
                radiance,
                evaluation.response,
                primePowerHeuristicOverPdf(
                        context.diagnostics, light.pdf, evaluation.pdf));
        primeCommitBsdfObservation(context, observation);
        return result;
    }
    PrimeBsdfComponents components;
    if (primeMaterialIsFoliage(surface.materialControl)) {
        components = primeEvaluateMinecraftFoliageComponents(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                light.direction,
                0.0,
                volumeStack,
                observation);
    } else {
        components = primeEvaluateOpaqueComponents(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                light.direction,
                0.0,
                volumeStack,
                observation);
    }
    float weightedInversePdf = primePowerHeuristicOverPdf(
            context.diagnostics, light.pdf, components.pdf);
    float3 result = primeTripleProduct(
            radiance,
            components.diffuseResponse + components.specularResponse,
            weightedInversePdf);
    primeCommitBsdfObservation(context, observation);
    return result;
}

float3 primeEstimateOfflineSun(
        inout PrimeIntegratorContext context,
        PathState path,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeOpenPbrVolumeStack volumeStack) {
    LightSample light = primeSampleSun(
            integrator,
            surface.position,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY));
    if (!primeAtmosphereDistantDirectionVisible(light.direction)) {
        return float3(0.0);
    }
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, light)) {
        return float3(0.0);
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            context, surface.position, surface.geometricNormal, light, volumeStack);
    if (shadow.hitDistance < PRIME_NRD_FP16_MAX) {
        return float3(0.0);
    }
    float3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, light) * shadow.transmittance;
    return path.throughput * primeEvaluateOfflineDirect(
            context, surface, viewDirection, light, radiance, volumeStack);
}

PrimeOfflineAreaRequest primePrepareOfflineArea(
        inout PrimeIntegratorContext context,
        PathState path,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeOfflineAreaRequest request = primeEmptyOfflineAreaRequest();
    AreaLightSample area = primeSampleAreaLight(
            context.diagnostics,
            surface.position,
            primeAreaLightReceiverNormal(surface, viewDirection),
            primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY).xy,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_SECONDARY));
    float3 radiance = primeResolveSampledAreaLightRadiance(area);
    float3 contribution = path.throughput * primeEvaluateOfflineDirect(
            context, surface, viewDirection, area.light, radiance, volumeStack);
    request.valid = area.light.pdf > 0.0
            && area.light.distance > 0.0
            && any(greaterThan(contribution, float3(0.0)));
    if (request.valid) {
        request.direction = area.light.direction;
        request.distance = area.light.distance;
        request.contribution = contribution;
        request.startingExtinction = volumeStack.count > 0u
                ? volumeStack.values[volumeStack.count - 1u].extinction
                : float3(0.0);
        request.startingMediumCount = volumeStack.count;
    }
    return request;
}

PrimeShadowTrace primeTraceOfflineAreaShadow(
        inout PrimeIntegratorContext context,
        float3 physicalPosition,
        PrimeOfflineAreaRequest request,
        PrimeOpenPbrVolumeStack startingVolumeStack) {
    uint startingMediumCount = min(
            request.startingMediumCount, PRIME_OPENPBR_MAX_VOLUME_STACK_SIZE);
    float3 startingExtinction0 = startingMediumCount > 0u
            ? primeShadowCanonicalExtinction(
                    startingVolumeStack.values[0].extinction)
            : float3(0.0);
    float3 startingExtinction1 = startingMediumCount > 1u
            ? primeShadowCanonicalExtinction(
                    startingVolumeStack.values[1].extinction)
            : float3(0.0);
    primeShadowPayload.opticalDepthMomentHitDistance = float4(0.0);
    primeShadowPayload.terminalExtinctionRayDistance = float4(
            float3(0.0),
            request.distance);
    primeShadowPayload.startingExtinction0Winding = float4(
            startingExtinction0, startingMediumCount > 0u ? 1.0 : 0.0);
    primeShadowPayload.startingExtinction1Winding = float4(
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
            float3(0.0),
            float3(1.0));
    result.hitDistance = primeNrdSanitizeHitDistance(
            primeShadowPayload.opticalDepthMomentHitDistance.w);
    primeRecordUnit(context.diagnostics, result.transmittance);
    return result;
}

PrimePathScatter primeSampleOfflinePathSurface(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimePathScatter result;
    result.volumeStack = volumeStack;
    PrimeNumericalObservation observation = primeBsdfObservationCreate();
    if (primeMaterialIsFoliage(surface.materialControl)) {
        PrimeCompactFoliageState state = primeMinecraftFoliageState(
                surface.baseColor,
                primeSurfaceShadingNormal(surface, viewDirection),
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                surface.t,
                volumeStack);
        result.bsdf = primeSampleMinecraftFoliageFromState(
                state, viewDirection, sampleValue, volumeStack, observation);
    } else if (primeMaterialIsTransmissive(surface.materialControl)) {
        PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
                surface.baseColor,
                primeSurfaceOpacity(surface),
                primeSurfaceOutwardShadingNormal(surface),
                surface.materialControl,
                surface.roughness,
                surface.opticalControl,
                viewDirection,
                surface.t,
                volumeStack,
                surface.adjacentBaseColor,
                surface.adjacentInterfaceControl);
        PrimeTransmissiveBsdfSample sampled =
                primeSampleOfflineMinecraftTransmissionFromState(
                        state,
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        surface.materialControl,
                        viewDirection,
                        sampleValue,
                        volumeStack,
                        observation);
        sampled = primeApplyAdjacentMediumTransition(
                sampled,
                state,
                volumeStack,
                surface.baseColor,
                primeSurfaceOpacity(surface),
                surface.materialControl,
                surface.opticalControl,
                surface.adjacentBaseColor,
                surface.adjacentInterfaceControl);
        result.bsdf = sampled.bsdfSample;
        result.volumeStack = sampled.volumeStack;
    } else {
        float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
        PrimeCompactOpaqueState state = primeOpaqueState(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                surface.t,
                volumeStack);
        result.bsdf = primeSampleOpaqueFromState(
                state,
                shadingNormal,
                viewDirection,
                sampleValue,
                volumeStack,
                observation);
    }
    primeCommitBsdfObservation(context, observation);
    return result;
}

bool primeAdvanceOfflinePath(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        PrimePreparedSampleBase bounceSample) {
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_PATH_ADVANCE, path.bounce);
    float3 nextThroughput = primeProductOver(path.throughput, bsdf.response, bsdf.pdf);
    primeRecordNonnegative(context.diagnostics, nextThroughput);
    if (all(equal(nextThroughput, float3(0.0)))) {
        return false;
    }
    path.throughput = nextThroughput;
    path.etaScale = primeEtaScaleAfterScatter(
            path.etaScale,
            bsdf.relativeEta,
            (bsdf.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u);
    primeRecordNonnegative(context.diagnostics, path.etaScale);
    path.physicalOrigin = surface.position;
    path.traceOrigin = primeOffsetRayOrigin(
            path.physicalOrigin, surface.geometricNormal, bsdf.direction);
    path.rayDirection = bsdf.direction;
    path.previousBsdfPdf = bsdf.pdf;
    path.flags = (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u
            ? PRIME_PATH_PREVIOUS_DISCRETE
            : 0u;

    if (!primeRussianRouletteApplies(
            path.bounce, PRIME_RUSSIAN_ROULETTE_START)) {
        return true;
    }
    float survival = primeOfflineRussianRouletteSurvival(
            path.throughput, path.etaScale);
    primeRecordUnit(context.diagnostics, survival);
    float rouletteSample = primeHashSample1D(
            bounceSample,
            PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    if (rouletteSample >= survival) {
        return false;
    }
    path.throughput = primeRussianRouletteReweight(path.throughput, survival);
    primeRecordNonnegative(context.diagnostics, path.throughput);
    return true;
}

PrimeOfflineSurfaceStep primeIntegrateOfflineSurface(
        inout PrimeIntegratorContext context,
        inout PathState path,
        IntegratorRecord integrator,
        inout PrimeOpenPbrVolumeStack volumeStack,
        inout float3 radiance,
        inout float primaryDistance,
        inout float3 primaryAlbedo,
        inout uint primaryMaterialFlags,
        SurfaceInteraction surface,
        bool deferArea) {
    PrimeOfflineSurfaceStep result;
    result.areaRequest = primeEmptyOfflineAreaRequest();
    result.continuation = false;
    if (!primeKnownHitKind(surface)) {
        return result;
    }
    if (surface.hitKind == PRIME_HIT_NONE) {
        primeAccumulate(
                context,
                radiance,
                primeEvaluateEnvironmentContribution(context, path, integrator));
        return result;
    }
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return result;
    }

    bool previousUsedAreaNee =
            (path.flags & PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE) != 0u;
    primeAccumulate(
            context,
            radiance,
            primeEvaluateHitEmission(
                    context, path, surface, previousUsedAreaNee));
    float3 viewDirection = -path.rayDirection;
    if (!(primaryDistance >= 0.0)) {
        primaryDistance = length(surface.position - primePush.cameraPosition);
        primaryAlbedo = surface.baseColor;
        primaryMaterialFlags = primeNrdGuideControl(
                surface.materialControl, surface.opticalControl);
    }

    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    bool neeEligible = primeClosureSupports(
            closureTraits, PRIME_MEASURE_SOLID_ANGLE);
    if (neeEligible) {
        primeAccumulate(
                context,
                radiance,
                primeEstimateOfflineSun(
                        context,
                        path,
                        integrator,
                        surface,
                        viewDirection,
                        preparedSample,
                        volumeStack));
        result.areaRequest = primePrepareOfflineArea(
                context,
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
    if (result.areaRequest.valid && !deferArea) {
        PrimeShadowTrace shadow = primeTraceShadow(
                context,
                surface.position,
                surface.geometricNormal,
                LightSample(
                        result.areaRequest.direction,
                        result.areaRequest.distance,
                        float3(0.0),
                        1.0,
                        0u),
                volumeStack);
        if (shadow.hitDistance >= PRIME_NRD_FP16_MAX) {
            primeAccumulate(
                    context,
                    radiance,
                    result.areaRequest.contribution * shadow.transmittance);
        }
        result.areaRequest = primeEmptyOfflineAreaRequest();
    }

    PrimePathScatter scatter = primeSampleOfflinePathSurface(
            context,
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
        return result;
    }
    if (!primeAdvanceOfflinePath(
            context, path, surface, bsdf, preparedSample)) {
        path.flags = usedAreaNee ? PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE : 0u;
        return result;
    }
    path.flags |= usedAreaNee ? PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE : 0u;
    path.bounce++;
    result.continuation = true;
    return result;
}

#endif
