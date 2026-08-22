#ifndef PRIME_TRANSPORT_CORE_SLANG
#define PRIME_TRANSPORT_CORE_SLANG

import glsl;
import "prime_abi.slang";
import "prime_abi_types.slang";
import "core/numerical_diagnostics.slang";
import "core/numerical.slang";
import "core/packed_geometry.slang";
import "core/queued_psr_math.slang";
import "core/render_config.slang";
import "core/sampling.slang";
import "core/transport_math.slang";
import "core/nrd_common.slang";
import "integrator/integration_result.slang";
import "integrator/path_contract.slang";
import "lighting/hit_emission.slang";
import "rt/shadow_payload.slang";
import "rt/surface_trace_diagnostic.slang";

#include "bsdf/adapter/bsdf.slang"

layout(location = 0) rayPayloadEXT TracePayload primeSurfacePayload;
layout(location = 1) rayPayloadEXT PrimeShadowPayload primeShadowPayload;

struct PrimeIntegratorContext {
    PrimeNumericalDiagnostics diagnostics;
};

PrimeIntegratorContext primeIntegratorContextCreate() {
    PrimeIntegratorContext context;
    context.diagnostics = primeNumericalDiagnosticsCreate();
    return context;
}

PrimeNumericalObservation primeBsdfObservationCreate() {
    return primeNumericalObservationCreate(
            primeWritesRawNumericalDiagnostic());
}

void primeCommitBsdfObservation(
        inout PrimeIntegratorContext context,
        PrimeNumericalObservation observation) {
    primeRecordNumerical(
            context.diagnostics,
            observation.flags,
            observation.firstField);
}

float3 primeSanitizeObservedDenoiseAlbedo(
        inout PrimeIntegratorContext context,
        float3 albedo) {
    PrimeNumericalObservation observation = primeBsdfObservationCreate();
    float3 result = primeSanitizeDenoiseAlbedo(albedo, observation);
    primeCommitBsdfObservation(context, observation);
    return result;
}

RayDesc primeIntegratorRay(
        float3 origin, float tMin, float3 direction, float tMax) {
    RayDesc ray;
    ray.Origin = origin;
    ray.TMin = tMin;
    ray.Direction = direction;
    ray.TMax = tMax;
    return ray;
}

struct PrimeDirectLightingSplit {
    float3 diffuse;
    float3 specular;
    float3 direction;
};

struct PrimeDenoiserState {
    bool hasPrimarySurface;
    bool reachedSolidAngle;
    float3 diffuseAlbedoProduct;
    float3 specularAlbedoProduct;
    bool diffusePath;
    uint primaryBounce;
};

SurfaceInteraction primeMissSurfaceInteraction(uint2 sourcePrimitive) {
    SurfaceInteraction surface;
    surface.position = float3(0.0);
    surface.t = 0.0;
    surface.geometricNormal = float3(0.0, 1.0, 0.0);
    surface.hitKind = PRIME_HIT_NONE;
    surface.baseColor = float3(0.0);
    surface.materialControl = 0u;
    surface.sectionIndex = sourcePrimitive.x;
    surface.emitterIndex = PRIME_NO_LIGHT_INDEX;
    surface.textureLod = 0.0;
    surface.opacity = 0.0;
    surface.shadingNormal = primePackOctahedralNormal(float3(0.0, 1.0, 0.0));
    surface.roughness = primeConfiguredDefaultLinearRoughness();
    surface.opticalControl = 0u;
    surface.motionZFlags = sourcePrimitive.y;
    surface.adjacentBaseColor = float3(0.0);
    surface.adjacentInterfaceControl = 0u;
    return surface;
}

void primeResetSurfacePayload(uint2 sourcePrimitive) {
    primeSurfacePayload.t = 0.0;
    primeSurfacePayload.geometricNormal = float3(0.0, 1.0, 0.0);
    primeSurfacePayload.hitKind = PRIME_HIT_NONE;
    primeSurfacePayload.baseColor = float3(0.0);
    primeSurfacePayload.materialControl = 0u;
    primeSurfacePayload.sectionIndex = sourcePrimitive.x;
    primeSurfacePayload.emitterIndex = PRIME_NO_LIGHT_INDEX;
    primeSurfacePayload.textureLod = 0.0;
    primeSurfacePayload.opacity = 0.0;
    primeSurfacePayload.shadingNormal =
            primePackOctahedralNormal(float3(0.0, 1.0, 0.0));
    primeSurfacePayload.roughness = primeConfiguredDefaultLinearRoughness();
    primeSurfacePayload.opticalControl = 0u;
    primeSurfacePayload.adjacentBaseColor = float3(0.0);
    primeSurfacePayload.adjacentInterfaceControl = 0u;
    primeSurfacePayload.position = float3(0.0);
    primeSurfacePayload.positionReserved = sourcePrimitive.y;
}

SurfaceInteraction primeSurfaceInteractionFromPayload() {
    SurfaceInteraction surface;
    uint packedHitKind = primeSurfacePayload.hitKind;
    surface.position = (packedHitKind & PRIME_HIT_KIND_MASK) == PRIME_HIT_NONE
            ? float3(0.0)
            : primeSurfacePayload.position;
    surface.t = primeSurfacePayload.t;
    surface.geometricNormal = primeSurfacePayload.geometricNormal;
    surface.hitKind = packedHitKind & PRIME_HIT_KIND_MASK;
    surface.baseColor = primeSurfacePayload.baseColor;
    surface.materialControl = primeSurfacePayload.materialControl;
    surface.sectionIndex = primeSurfacePayload.sectionIndex;
    surface.emitterIndex = primeSurfacePayload.emitterIndex;
    surface.textureLod = primeSurfacePayload.textureLod;
    surface.opacity = primeSurfacePayload.opacity;
    surface.shadingNormal = primeSurfacePayload.shadingNormal;
    surface.roughness = primeSurfacePayload.roughness;
    surface.opticalControl = primeSurfacePayload.opticalControl;
    surface.motionZFlags = primeSurfacePayload.positionReserved;
    surface.adjacentBaseColor = primeSurfacePayload.adjacentBaseColor;
    surface.adjacentInterfaceControl = surface.hitKind == PRIME_HIT_NONE
            ? packedHitKind & PRIME_TRACE_DIAGNOSTIC_MASK
            : primeSurfacePayload.adjacentInterfaceControl;
    return surface;
}

interface IPrimeSurfaceTracePolicy {
    void trace(
            float3 origin,
            float3 direction,
            uint2 sourcePrimitive,
            uint pathClass);
}

struct PrimeScalarSurfaceTracePolicy : IPrimeSurfaceTracePolicy {
    void trace(
            float3 origin,
            float3 direction,
            uint2 sourcePrimitive,
            uint pathClass) {
        uint rayFlags = sourcePrimitive.x == PRIME_NO_PRIMITIVE_INDEX
                ? gl_RayFlagsNoneEXT
                : gl_RayFlagsNoOpaqueEXT;
        traceRayEXT(primeScene, rayFlags, 0xff, 0, 1, 0,
                origin, 0.0, direction, 1000000.0, 0);
    }
};

struct PrimeSerSurfaceTracePolicy : IPrimeSurfaceTracePolicy {
    void trace(
            float3 origin,
            float3 direction,
            uint2 sourcePrimitive,
            uint pathClass) {
        int payloadLocation = 0;
        HitObject hitObject;
        hitObjectTraceRayEXT(
                hitObject,
                primeScene,
                sourcePrimitive.x == PRIME_NO_PRIMITIVE_INDEX
                        ? gl_RayFlagsNoneEXT
                        : gl_RayFlagsNoOpaqueEXT,
                0xff,
                0,
                1,
                0,
                origin,
                0.0,
                direction,
                1000000.0,
                payloadLocation);
        bool hit = hitObjectIsHitEXT(hitObject);
        uint coherenceHint = ((pathClass & 0x3u) << 10u)
                | (hit ? (uint(hitObjectGetGeometryIndexEXT(hitObject)) & 0x3u) << 8u : 0u)
                | (hit ? uint(hitObjectGetInstanceIdEXT(hitObject)) & 0xffu : 0u);
        reorderThreadEXT(hitObject, coherenceHint, 12u);
        hitObjectExecuteShaderEXT(hitObject, payloadLocation);
    }
};

#if defined(PRIME_ENABLE_SER)
typealias PrimeSurfaceTracePolicy = PrimeSerSurfaceTracePolicy;
#else
typealias PrimeSurfaceTracePolicy = PrimeScalarSurfaceTracePolicy;
#endif

SurfaceInteraction primeTraceSurfaceClassified<TracePolicy>(
        TracePolicy policy,
        float3 origin,
        float3 direction,
        uint2 sourcePrimitive,
        uint pathClass)
    where TracePolicy : IPrimeSurfaceTracePolicy
{
    primeResetSurfacePayload(sourcePrimitive);
    policy.trace(origin, direction, sourcePrimitive, pathClass);
    return primeSurfaceInteractionFromPayload();
}

SurfaceInteraction primeTraceSurface(float3 origin, float3 direction) {
    return primeTraceSurfaceClassified(
            PrimeSurfaceTracePolicy(),
            origin,
            direction,
            uint2(PRIME_NO_PRIMITIVE_INDEX),
            0u);
}

SurfaceInteraction primeTraceSurface(
        float3 origin, float3 direction, uint2 sourcePrimitive) {
    return primeTraceSurfaceClassified(
            PrimeSurfaceTracePolicy(), origin, direction, sourcePrimitive, 0u);
}

SurfaceInteraction primeTraceSurfaceWithoutReorder(
        float3 origin, float3 direction) {
    return primeTraceSurfaceClassified(
            PrimeScalarSurfaceTracePolicy(),
            origin,
            direction,
            uint2(PRIME_NO_PRIMITIVE_INDEX),
            0u);
}

SurfaceInteraction primeTraceSurfaceWithoutReorder(
        float3 origin, float3 direction, uint2 sourcePrimitive) {
    return primeTraceSurfaceClassified(
            PrimeScalarSurfaceTracePolicy(),
            origin,
            direction,
            sourcePrimitive,
            0u);
}

bool primeKnownHitKind(SurfaceInteraction surface) {
    return surface.hitKind == PRIME_HIT_NONE
            || surface.hitKind == PRIME_HIT_SURFACE;
}

bool primePreviousCannotUseMis(PathState path) {
    return path.bounce == 0u
            || (path.flags & PRIME_PATH_PREVIOUS_DISCRETE) != 0u;
}

float3 primeSurfaceOutwardShadingNormal(SurfaceInteraction surface) {
    float3 shadingNormal = primeUnpackOctahedralNormal(surface.shadingNormal);
    return dot(shadingNormal, surface.geometricNormal) >= 0.0
            ? shadingNormal
            : -shadingNormal;
}

float3 primeSurfaceShadingNormal(
        SurfaceInteraction surface, float3 viewDirection) {
    float3 shadingNormal = primeSurfaceOutwardShadingNormal(surface);
    return dot(surface.geometricNormal, viewDirection) >= 0.0
            ? shadingNormal
            : -shadingNormal;
}

float primeSurfaceOpacity(SurfaceInteraction surface) {
    return clamp(surface.opacity, 0.0, 1.0);
}

float primeSurfaceLinearRoughness(SurfaceInteraction surface) {
    return primeMaterialEffectiveLinearRoughness(
            surface.materialControl, surface.roughness);
}

PrimeClosureTraits primeSurfaceClosureTraits(
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack volumeStack) {
    if (primeMaterialIsTransmissive(surface.materialControl)) {
        return primeMinecraftTransmissionClosureTraits(
                primeSurfaceOutwardShadingNormal(surface),
                surface.materialControl,
                surface.roughness,
                surface.opticalControl,
                viewDirection,
                volumeStack,
                surface.adjacentInterfaceControl);
    }
    if (primeMaterialIsFoliage(surface.materialControl)) {
        return primeFoliageClosureTraits(
                surface.roughness,
                surface.opticalControl,
                surface.materialControl);
    }
    return primeOpaqueClosureTraits(
            surface.roughness,
            surface.opticalControl,
            surface.materialControl);
}

void primeAccumulate(
        inout PrimeIntegratorContext context,
        inout float3 accumulator,
        float3 contribution) {
    primeRecordRadiance(context.diagnostics, contribution);
    accumulator += contribution;
    primeRecordRadiance(context.diagnostics, accumulator);
}

void primeAccumulateAfterPrimary(
        inout PrimeIntegratorContext context,
        inout PrimeIntegrationResult result,
        bool diffusePath,
        float3 contribution) {
    if (diffusePath) {
        primeAccumulate(context, result.radiance.diffuse, contribution);
    } else {
        primeAccumulate(context, result.radiance.specular, contribution);
    }
}

void primeAccumulateTransparentBranch(
        inout PrimeIntegratorContext context,
        inout PrimeTransparentBranchResult result,
        bool diffusePath,
        float3 contribution) {
    if (diffusePath) {
        primeAccumulate(context, result.diffuseRadiance, contribution);
    } else {
        primeAccumulate(context, result.specularRadiance, contribution);
    }
}

float3 primeEvaluateDeltaHitEmission(
        inout PrimeIntegratorContext context,
        PathState path,
        SurfaceInteraction surface) {
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_EMISSION, path.bounce);
    float3 radiance =
            (surface.materialControl & PRIME_MATERIAL_VISIBLE_EMISSION) != 0u
            ? surface.baseColor
                    * PRIME_LEVEL_15_BLOCK_INTENSITY
                    * primeBlockLightRadianceMultiplier()
            : primeEvaluateHitEmitterRadiance(surface, path.rayDirection);
    return path.throughput * radiance;
}

bool primeApplySegmentMedium(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        PrimeOpenPbrVolumeStack volumeStack) {
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_MEDIUM, path.bounce);
    if (volumeStack.count == 0u) {
        return true;
    }
    PrimeOpenPbrVolume medium = volumeStack.values[volumeStack.count - 1u];
    path.throughput *= primeSegmentTransmittance(medium.extinction, surface.t);
    primeRecordNonnegative(context.diagnostics, path.throughput);
    return !all(path.throughput == float3(0.0));
}

struct PrimePathScatter {
    BsdfSample bsdf;
    PrimeOpenPbrVolumeStack volumeStack;
};

PrimePathScatter primeSamplePathSurfaceWithMinimumRoughness(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        float minimumLinearRoughness) {
    PrimePathScatter result;
    result.volumeStack = volumeStack;
    PrimeNumericalObservation observation = primeBsdfObservationCreate();
    if (primeMaterialIsFoliage(surface.materialControl)) {
        PrimeCompactFoliageState state =
                primeMinecraftFoliageStateWithMinimumRoughness(
                        surface.baseColor,
                        primeSurfaceShadingNormal(surface, viewDirection),
                        surface.roughness,
                        surface.opticalControl,
                        surface.materialControl,
                        viewDirection,
                        surface.t,
                        volumeStack,
                        minimumLinearRoughness);
        result.bsdf = primeSampleMinecraftFoliageFromState(
                state, viewDirection, sampleValue, volumeStack, observation);
    } else if (primeMaterialIsTransmissive(surface.materialControl)) {
        float3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
        PrimeCompactTransmissionState state =
                primeMinecraftBoundaryTransmissionStateWithMinimumRoughness(
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        outwardNormal,
                        surface.materialControl,
                        surface.roughness,
                        surface.opticalControl,
                        viewDirection,
                        surface.t,
                        volumeStack,
                        minimumLinearRoughness,
                        surface.adjacentBaseColor,
                        surface.adjacentInterfaceControl);
        PrimeTransmissiveBsdfSample sampled =
                primeSampleMinecraftTransmissionCompleteFromState(
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
        float3 shadingNormal =
                primeSurfaceShadingNormal(surface, viewDirection);
        PrimeCompactOpaqueState state = primeOpaqueStateWithMinimumRoughness(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                surface.t,
                volumeStack,
                minimumLinearRoughness);
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

PrimePathScatter primeSamplePathSurface(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeSamplePathSurfaceWithMinimumRoughness(
            context, surface, viewDirection, sampleValue, volumeStack, 0.0);
}

struct PrimeGuidedPathScatter {
    PrimePathScatter scatter;
    PrimeDenoiseAlbedos albedos;
};

PrimeGuidedPathScatter primeSampleGuidedPathSurface(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolumeStack volumeStack,
        uint bounce) {
    PrimeGuidedPathScatter result;
    result.scatter.volumeStack = volumeStack;
    PrimeNumericalObservation observation = primeBsdfObservationCreate();
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_SURFACE, bounce);
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
        result.albedos = primeDenoiseAlbedosFromState(
                state,
                viewDirection,
                PRIME_DENOISE_CLOSURE_FOLIAGE,
                observation);
        primeSetNumericalContext(
                context.diagnostics,
                PRIME_NUMERICAL_STAGE_BSDF_SAMPLE,
                bounce);
        result.scatter.bsdf = primeSampleMinecraftFoliageFromState(
                state, viewDirection, sampleValue, volumeStack, observation);
    } else if (primeMaterialIsTransmissive(surface.materialControl)) {
        float3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
        PrimeCompactTransmissionState state =
                primeMinecraftBoundaryTransmissionState(
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        outwardNormal,
                        surface.materialControl,
                        surface.roughness,
                        surface.opticalControl,
                        viewDirection,
                        surface.t,
                        volumeStack,
                        surface.adjacentBaseColor,
                        surface.adjacentInterfaceControl);
        result.albedos = primeDenoiseAlbedosFromState(
                state,
                viewDirection,
                PRIME_DENOISE_CLOSURE_TRANSMISSIVE,
                observation);
        primeSetNumericalContext(
                context.diagnostics,
                PRIME_NUMERICAL_STAGE_BSDF_SAMPLE,
                bounce);
        PrimeTransmissiveBsdfSample sampled =
                primeSampleMinecraftTransmissionCompleteFromState(
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
        result.scatter.bsdf = sampled.bsdfSample;
        result.scatter.volumeStack = sampled.volumeStack;
    } else {
        float3 shadingNormal =
                primeSurfaceShadingNormal(surface, viewDirection);
        PrimeCompactOpaqueState state = primeOpaqueState(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                surface.t,
                volumeStack);
        result.albedos = primeDenoiseAlbedosFromState(
                state,
                viewDirection,
                PRIME_DENOISE_CLOSURE_OPAQUE,
                observation);
        primeSetNumericalContext(
                context.diagnostics,
                PRIME_NUMERICAL_STAGE_BSDF_SAMPLE,
                bounce);
        result.scatter.bsdf = primeSampleOpaqueFromState(
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

bool primeHasScatter(BsdfSample bsdf) {
    return bsdf.eventFlags != 0u;
}

bool primeIsDiscreteSample(BsdfSample bsdf) {
    return (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u;
}

bool primeIsSolidAngleSample(BsdfSample bsdf) {
    uint solidAngleFlags =
            PRIME_BSDF_EVENT_DIFFUSE | PRIME_BSDF_EVENT_GLOSSY;
    return (bsdf.eventFlags & solidAngleFlags) != 0u
            && !primeIsDiscreteSample(bsdf);
}

static const uint PRIME_SHARC_EVENT_DISCRETE = 0u;
static const uint PRIME_SHARC_EVENT_DIFFUSE = 1u;
static const uint PRIME_SHARC_EVENT_GLOSSY = 2u;

uint primeSharcEvent(BsdfSample bsdf) {
    if (primeIsDiscreteSample(bsdf)) {
        return PRIME_SHARC_EVENT_DISCRETE;
    }
    return (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u
            ? PRIME_SHARC_EVENT_DIFFUSE
            : PRIME_SHARC_EVENT_GLOSSY;
}

bool primeApplyPathScatter(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf) {
    primeSetNumericalContext(
            context.diagnostics,
            PRIME_NUMERICAL_STAGE_PATH_ADVANCE,
            path.bounce);
    float3 nextThroughput =
            primeProductOver(path.throughput, bsdf.response, bsdf.pdf);
    primeRecordNonnegative(context.diagnostics, nextThroughput);
    if (all(nextThroughput == float3(0.0))) {
        return false;
    }
    path.throughput = nextThroughput;
    path.etaScale = primeEtaScaleAfterScatter(
            path.etaScale,
            bsdf.relativeEta,
            (bsdf.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u);
    primeRecordNonnegative(context.diagnostics, path.etaScale);
    path.physicalOrigin = surface.position;
    path.sourcePrimitive = uint2(
            surface.sectionIndex, primeSurfaceTriangleIndex(surface));
    path.sourceReserved = 0u;
    path.rayDirection = bsdf.direction;
    path.previousBsdfPdf = bsdf.pdf;
    path.flags = (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u
            ? PRIME_PATH_PREVIOUS_DISCRETE
            : 0u;
    return true;
}

bool primeAdvancePathWithoutRoulette(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        uint previousLightNormal,
        float surfaceLinearRoughness) {
    if (!primeApplyPathScatter(context, path, surface, bsdf)) {
        return false;
    }
    path.previousLightNormal = previousLightNormal;
    path.previousSharcRoughness = surfaceLinearRoughness;
    path.previousSharcEvent = primeSharcEvent(bsdf);
    return true;
}

PrimeQueuedPsrState primeAppendQueuedPsrDiscrete(
        PrimeQueuedPsrState state,
        float3 previousPosition,
        SurfaceInteraction surface,
        BsdfSample bsdf) {
    return primeAppendQueuedPsrState(
            state,
            primePush.cameraPosition,
            previousPosition,
            surface.position,
            surface.geometricNormal,
            (bsdf.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u);
}

#endif
