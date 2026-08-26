#language slang 2026
module "transport/offline/surface.slang";

import "contract/light_sample.slang";

import "policy/runtime/config.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "state/path.slang";
import "service/bsdf/contract/sample.slang";
import "model/atmosphere/sky.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "math/sampling.slang";
import "math/transport.slang";
import "service/light/emission.slang";
import "service/light/sample.slang";
import "service/light/area.slang";
import "model/material/defaults.slang";
import "model/material/transparent.slang";
import "service/bsdf/contract/components.slang";
import "service/bsdf/dispatch/sample.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/trace/context.slang";
import "service/trace/surface.slang";
import "contract/light.slang";
import "transport/direct/evaluate.slang";
import "transport/direct/primary.slang";
import "transport/direct/selection.slang";
import "transport/direct/shadow.slang";
import "transport/scatter/path_update.slang";
import "transport/scatter/contract.slang";
import "state/offline/contract.slang";
import "transport/surface/access.slang";
import "transport/surface/accumulate.slang";
import "transport/surface/emission.slang";
import "transport/surface/medium.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";

public struct PrimeOfflineAreaRequest {
    public float3 direction;
    public float distance;
    public float3 contribution;
    public bool valid;
    public float3 startingExtinction;
    public uint startingMediumCount;
    public uint2 targetEmitter;
};

public struct PrimeOfflineSurfaceStep {
    public PrimeOfflineAreaRequest areaRequest;
    public bool continuation;
};

public PrimeOfflineAreaRequest primeEmptyOfflineAreaRequest() {
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

public PrimeOfflineAreaRequest primePrepareOfflineArea(
        inout PrimeIntegratorContext context,
        PathState path,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeOfflineAreaRequest request = primeEmptyOfflineAreaRequest();
    AreaLightSample area = primeSampleAreaLight(
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
            primeUsesAirGap(),
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

public PrimeShadowTrace primeTraceOfflineAreaShadow(
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

public bool primeAdvanceOfflinePath(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        PrimePreparedSampleBase bounceSample) {
    if (!primeApplyPathScatter(context, path, surface, bsdf)) {
        return false;
    }

    if (!primeRussianRouletteApplies(
            primePathBounce(path.control), PRIME_RUSSIAN_ROULETTE_START)) {
        return true;
    }
    float survival = primeRussianRouletteSurvival(
            path.throughput, path.etaScale);
    float rouletteSample = primeHashSample1D(
            bounceSample,
            PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    if (rouletteSample >= survival) {
        return false;
    }
    path.throughput = primeRussianRouletteReweight(path.throughput, survival);
    return true;
}

public PrimeOfflineSurfaceStep primeIntegrateOfflineSurface(
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
            (primePathFlags(path.control)
                    & PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE) != 0u;
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
            primePreparePathSample(
                    path.sampleSeed,
                    primeSampleIndex(),
                    primePathBounce(path.control) + 1u);
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack, primeUsesAirGap());
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
    path.control = primePathWithPreviousLightNormal(
            path.control,
            usedAreaNee
                    ? primeAreaLightReceiverNormal(surface, viewDirection)
                    : 0u);
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
            volumeStack,
            primeUsesAirGap());
    BsdfSample bsdf = scatter.bsdf;
    volumeStack = scatter.volumeStack;
    if (!primeHasScatter(bsdf)) {
        path.physicalOrigin = surface.position;
        path.sourcePrimitive = uint2(
                surface.sectionIndex, primeSurfaceTriangleIndex(surface));
        path.control = primePathWithFlags(
                path.control,
                usedAreaNee ? PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE : 0u);
        return result;
    }
    if (!primeAdvanceOfflinePath(
            context, path, surface, bsdf, preparedSample)) {
        path.control = primePathWithFlags(
                path.control,
                usedAreaNee ? PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE : 0u);
        return result;
    }
    path.control = primePathWithAddedFlags(
            path.control,
            usedAreaNee ? PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE : 0u);
    path.control = primeIncrementPathBounce(path.control);
    result.continuation = true;
    return result;
}

