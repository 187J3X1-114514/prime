#ifndef PRIME_OFFLINE_INTEGRATOR_SLANG
#define PRIME_OFFLINE_INTEGRATOR_SLANG

struct PrimeOfflineAreaRequest {
    float3 direction;
    float distance;
    float3 contribution;
    bool valid;
    float3 startingExtinction;
    uint startingMediumCount;
    uint2 targetEmitter;
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
    request.targetEmitter = uint2(PRIME_NO_PRIMITIVE_INDEX);
    return request;
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
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    float3 contribution = path.throughput * primeEvaluateCompleteDirect(
            context,
            surface,
            viewDirection,
            shadingNormal,
            area.light,
            radiance,
            volumeStack,
            true);
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
        request.targetEmitter = uint2(area.sectionIndex, area.emitterIndex);
    }
    return request;
}

PrimeShadowTrace primeTraceOfflineAreaShadow(
        inout PrimeIntegratorContext context,
        float3 physicalPosition,
        uint2 sourcePrimitive,
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
    return primeTracePreparedShadow(
            context,
            physicalPosition,
            request.direction,
            request.distance,
            sourcePrimitive,
            request.targetEmitter,
            startingExtinction0,
            startingExtinction1,
            startingMediumCount);
}

bool primeAdvanceOfflinePath(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        PrimePreparedSampleBase bounceSample) {
    if (!primeApplyPathScatter(context, path, surface, bsdf)) {
        return false;
    }

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
                path.throughput * primeEstimateDirectSun(
                        context,
                        integrator,
                        surface,
                        viewDirection,
                        primeSobolSample2D(
                                preparedSample,
                                PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                                PRIME_SAMPLE_DIMENSION_PRIMARY),
                        volumeStack,
                        1.0));
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
                surface,
                LightSample(
                        result.areaRequest.direction,
                        result.areaRequest.distance,
                        float3(0.0),
                        1.0,
                        0u),
                result.areaRequest.targetEmitter,
                volumeStack);
        if (shadow.hitDistance >= PRIME_NRD_FP16_MAX) {
            primeAccumulate(
                    context,
                    radiance,
                    result.areaRequest.contribution * shadow.transmittance);
        }
        result.areaRequest = primeEmptyOfflineAreaRequest();
    }

    PrimePathScatter scatter = primeSamplePathSurface(
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
        path.sourcePrimitive = uint2(
                surface.sectionIndex, primeSurfaceTriangleIndex(surface));
        path.sourceReserved = 0u;
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
