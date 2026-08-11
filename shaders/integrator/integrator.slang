#ifndef PRIME_INTEGRATOR_SLANG
#define PRIME_INTEGRATOR_SLANG

import glsl;

#include "core/common.slang"
#include "core/sampling.slang"
import "core/queued_psr_math.slang";
import "core/transparent_checkerboard.slang";
#include "core/nrd_common.slang"

// The BSDF adapter's internal hooks predate Slang's explicit per-invocation diagnostic object.
// Production transport classifies every consumed adapter result at this boundary; these hooks
// remain intentionally empty until the post-migration diagnostic redesign recorded in the issue
// log, avoiding hidden mutable shader state in the meantime.
void primeRecordNonFinite(float value) {}
void primeRecordNonFinite(float3 value) {}
void primeRecordNonnegative(float value) {}
void primeRecordNonnegative(float3 value) {}
void primeRecordRadiance(float3 value) {}
void primeRecordUnit(float value) {}
void primeRecordUnit(float3 value) {}
void primeRecordDirection(float3 value) {}

#include "bsdf/adapter/bsdf.slang"
#include "lighting/lights.slang"
#include "rt/shadow_payload.slang"

// The entire estimator operates in linear Rec.2020 D65. Scheduling changes may move this state
// between kernels, but they must not reinterpret it as encoded sRGB or another RGB basis.

static const uint PRIME_HIT_NONE = 0u;
static const uint PRIME_HIT_SURFACE = 1u;
static const uint PRIME_PATH_PREVIOUS_DISCRETE = 1u;

// Payload locations are part of the independently compiled RT pipeline ABI. Slang's typed
// TraceRay overload assigns locations from each entry point's live payload set, so a shader that
// only traces one ray kind can silently disagree with the hit/miss stages. Explicit GLSL-style
// payload globals keep the legacy location-0 surface/location-1 shadow contract deterministic.
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

RayDesc primeIntegratorRay(float3 origin, float tMin, float3 direction, float tMax) {
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

// This is the complete Monte Carlo estimate, split only because realtime denoisers consume
// different signal classes. Adding these mutually exclusive partitions reconstructs the beauty
// sample exactly; no guide is allowed to feed back into these values.
struct PrimePathRadiance {
    float3 diffuse;
    float3 specular;
    float3 stable;
    float3 unshadowedSun;
    float sunVisibility;
};

// Auxiliary observations used by reconstruction. They describe the sampled path and primary
// surface, but are not radiance contributions and therefore cannot change the reference estimate.
struct PrimeDenoiserGuides {
    float primaryDistance;
    float specularHitDistance;
    float diffuseHitDistance;
    float sunPenumbra;
    float3 primaryAlbedo;
    uint primaryHitKind;
    float3 primaryNormal;
    uint primaryMaterialFlags;
    float3 primarySpecularAlbedo;
    float primaryLinearRoughness;
    float3 primaryPosition;
    float3 primaryPreviousPosition;
    bool primaryHasMotion;
    float3 diffuseDirection;
    float3 specularDirection;
    float3 primaryAreaDiffuse;
    float3 primaryAreaSpecular;
    float3 primaryAreaDirection;
};

struct PrimeIntegrationResult {
    PrimePathRadiance radiance;
    PrimeDenoiserGuides guides;
    float3 reflectionDiffuseRadiance;
    float3 reflectionSpecularRadiance;
    PrimeDenoiserGuides transmissionGuides;
    PrimeDenoiserGuides reflectionGuides;
    float transmissionAnchorDistance;
    bool reflectionDirectionalGuide;
    bool transparentPrimary;
};

struct PrimeTransparentBranchResult {
    float3 diffuseRadiance;
    float3 specularRadiance;
    PrimeDenoiserGuides guides;
    float firstHitDistance;
    float anchorDistance;
    bool directionalGuide;
};

// Bookkeeping that maps a complete model path into the signal/guide contract. Keeping it in a
// named state object makes the estimator loop independent of any particular NRD or NGX resource.
struct PrimeDenoiserState {
    bool hasPrimarySurface;
    bool reachedSolidAngle;
    float3 diffuseAlbedoProduct;
    float3 specularAlbedoProduct;
    bool diffusePath;
    uint primaryBounce;
};

float3 primeResolveIntegrationRadiance(PrimeIntegrationResult result) {
    return result.radiance.diffuse
            + result.radiance.specular
            + result.reflectionDiffuseRadiance
            + result.reflectionSpecularRadiance
            + result.radiance.stable
            + result.radiance.unshadowedSun * result.radiance.sunVisibility;
}

PrimeDenoiserGuides primeEmptyDenoiserGuides() {
    PrimeDenoiserGuides guides;
    guides.primaryDistance = -1.0;
    guides.specularHitDistance = 0.0;
    guides.diffuseHitDistance = 0.0;
    guides.sunPenumbra = 0.0;
    guides.primaryAlbedo = float3(0.0);
    guides.primaryHitKind = PRIME_HIT_NONE;
    guides.primaryNormal = float3(0.0, 1.0, 0.0);
    guides.primaryMaterialFlags = 0u;
    guides.primarySpecularAlbedo = float3(0.0);
    guides.primaryLinearRoughness = PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    guides.primaryPosition = float3(0.0);
    guides.primaryPreviousPosition = float3(0.0);
    guides.primaryHasMotion = false;
    guides.diffuseDirection = float3(0.0);
    guides.specularDirection = float3(0.0);
    guides.primaryAreaDiffuse = float3(0.0);
    guides.primaryAreaSpecular = float3(0.0);
    guides.primaryAreaDirection = float3(0.0);
    return guides;
}

float3 primeOffsetRayOrigin(float3 physicalPosition, float3 normal, float3 direction) {
    // The physical shading point remains unchanged for BSDF/light/PDF evaluation. Only the
    // traversal origin receives this offset, preventing a geometric epsilon from changing the
    // estimator or a future area-light solid-angle conversion.
    float side = dot(normal, direction) >= 0.0 ? 1.0 : -1.0;
    return physicalPosition + normal * (side * 0.001);
}

SurfaceInteraction primeTraceSurfaceClassified(
        inout PrimeIntegratorContext context,
        float3 origin,
        float3 direction,
        uint pathClass) {
    primeSurfacePayload.t = 0.0;
    primeSurfacePayload.geometricNormal = float3(0.0, 1.0, 0.0);
    primeSurfacePayload.hitKind = PRIME_HIT_NONE;
    primeSurfacePayload.baseColor = float3(0.0);
    primeSurfacePayload.materialControl = 0u;
    primeSurfacePayload.sectionIndex = 0u;
    primeSurfacePayload.emitterIndex = PRIME_NO_LIGHT_INDEX;
    primeSurfacePayload.textureLod = 0.0;
    primeSurfacePayload.opacity = 0.0;
    primeSurfacePayload.shadingNormal = primePackOctahedralNormal(float3(0.0, 1.0, 0.0));
    primeSurfacePayload.roughness = primeDefaultLinearRoughness();
    primeSurfacePayload.opticalControl = 0u;
    primeSurfacePayload.adjacentBaseColor = float3(0.0);
    primeSurfacePayload.adjacentInterfaceControl = 0u;
#if defined(PRIME_ENABLE_SER) && !defined(PRIME_DISABLE_SER_REORDER)
    // Hit objects decouple traversal from closest-hit/miss execution. The reorder point groups
    // those continuations by shader first and by the low section-index bits second, improving
    // both branch and primitive-buffer locality in incoherent terrain. Keep this helper narrow:
    // every caller-local value live here becomes state that the implementation may need to save
    // and restore across invocation repacking.
    int payloadLocation = 0;
    HitObject hitObject;
    hitObjectTraceRayEXT(
            hitObject,
            primeScene,
            gl_RayFlagsNoneEXT,
            0xff,
            0,
            1,
            0,
            origin,
            0.0,
            direction,
            1000000.0,
            payloadLocation);
    // Keep six section bits for primitive-buffer locality and reserve two bits for the caller's
    // transport class. Reordered invocations therefore stay coherent after returning to ordinary,
    // transparent-reflection or transparent-transmission shading.
    uint coherenceHint = ((pathClass & 0x3u) << 6u)
            | (hitObjectIsHitEXT(hitObject)
                    ? uint(hitObjectGetInstanceIdEXT(hitObject)) & 0x3fu
                    : 0u);
    reorderThreadEXT(hitObject, coherenceHint, 8u);
    hitObjectExecuteShaderEXT(hitObject, payloadLocation);
#else
    traceRayEXT(primeScene, gl_RayFlagsNoneEXT, 0xff, 0, 1, 0,
            origin, 0.0, direction, 1000000.0, 0);
#endif
    SurfaceInteraction surface;
    uint packedHitKind = primeSurfacePayload.hitKind;
    surface.position = (packedHitKind & PRIME_HIT_KIND_MASK) == PRIME_HIT_NONE
            ? float3(0.0)
            : origin + direction * primeSurfacePayload.t;
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
    surface.motionZFlags = packedHitKind & ~PRIME_HIT_KIND_MASK;
    surface.adjacentBaseColor = primeSurfacePayload.adjacentBaseColor;
    surface.adjacentInterfaceControl = primeSurfacePayload.adjacentInterfaceControl;
    return surface;
}

SurfaceInteraction primeTraceSurface(
        inout PrimeIntegratorContext context, float3 origin, float3 direction) {
    return primeTraceSurfaceClassified(context, origin, direction, 0u);
}

SurfaceInteraction primeTraceSurfaceWithoutReorder(
        inout PrimeIntegratorContext context, float3 origin, float3 direction) {
#if !defined(PRIME_ENABLE_SER)
    return primeTraceSurface(context, origin, direction);
#else
    // The sparse tail keeps the complete result, medium and denoiser state live across its
    // local loop. Repacking there spills more state than it recovers through coherence, so this
    // entry deliberately executes the same trace without the SER reorder point.
    primeSurfacePayload.t = 0.0;
    primeSurfacePayload.geometricNormal = float3(0.0, 1.0, 0.0);
    primeSurfacePayload.hitKind = PRIME_HIT_NONE;
    primeSurfacePayload.baseColor = float3(0.0);
    primeSurfacePayload.materialControl = 0u;
    primeSurfacePayload.sectionIndex = 0u;
    primeSurfacePayload.emitterIndex = PRIME_NO_LIGHT_INDEX;
    primeSurfacePayload.textureLod = 0.0;
    primeSurfacePayload.opacity = 0.0;
    primeSurfacePayload.shadingNormal =
            primePackOctahedralNormal(float3(0.0, 1.0, 0.0));
    primeSurfacePayload.roughness = primeDefaultLinearRoughness();
    primeSurfacePayload.opticalControl = 0u;
    primeSurfacePayload.adjacentBaseColor = float3(0.0);
    primeSurfacePayload.adjacentInterfaceControl = 0u;
    traceRayEXT(
            primeScene,
            gl_RayFlagsNoneEXT,
            0xff,
            0,
            1,
            0,
            origin,
            0.0,
            direction,
            1000000.0,
            0);

    SurfaceInteraction surface;
    uint packedHitKind = primeSurfacePayload.hitKind;
    surface.position = (packedHitKind & PRIME_HIT_KIND_MASK) == PRIME_HIT_NONE
            ? float3(0.0)
            : origin + direction * primeSurfacePayload.t;
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
    surface.motionZFlags = packedHitKind & ~PRIME_HIT_KIND_MASK;
    surface.adjacentBaseColor = primeSurfacePayload.adjacentBaseColor;
    surface.adjacentInterfaceControl = primeSurfacePayload.adjacentInterfaceControl;
    return surface;
#endif
}

bool primeKnownHitKind(SurfaceInteraction surface) {
    return surface.hitKind == PRIME_HIT_NONE || surface.hitKind == PRIME_HIT_SURFACE;
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

float3 primeSurfaceShadingNormal(SurfaceInteraction surface, float3 viewDirection) {
    float3 shadingNormal = primeSurfaceOutwardShadingNormal(surface);
    return dot(surface.geometricNormal, viewDirection) >= 0.0
            ? shadingNormal
            : -shadingNormal;
}

uint primeAreaLightReceiverNormal(
        SurfaceInteraction surface, float3 viewDirection) {
    if (primeMaterialIsTransmissive(surface.materialControl)
            || primeMaterialIsFoliage(surface.materialControl)) {
        return 0u;
    }
    return primePackLightReceiverNormal(
            primeSurfaceShadingNormal(surface, viewDirection));
}

float primeSurfaceOpacity(SurfaceInteraction surface) {
    return clamp(surface.opacity, 0.0, 1.0);
}

float primeSurfaceLinearRoughness(SurfaceInteraction surface) {
    return clamp(surface.roughness, 0.0, 1.0);
}

PrimeClosureTraits primeSurfaceClosureTraits(
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimeRcVolumeStack volumeStack) {
    if (primeMaterialIsTransmissive(surface.materialControl)) {
        PrimeCompiledClosure closure = primeCompileMinecraftTransmissionClosure(
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
        return closure.traits;
    }
    if (primeMaterialIsFoliage(surface.materialControl)) {
        PrimeCompiledClosure closure = primeCompileFoliageClosure(
                surface.baseColor,
                primeSurfaceOutwardShadingNormal(surface),
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                surface.t,
                volumeStack);
        return closure.traits;
    }
    PrimeCompiledClosure closure = primeCompileOpaqueClosure(
            surface.baseColor,
            primeSurfaceOutwardShadingNormal(surface),
            surface.roughness,
            surface.opticalControl,
            surface.materialControl,
            viewDirection,
            surface.t,
            volumeStack);
    return closure.traits;
}

struct PrimeShadowTrace {
    float3 transmittance;
    float hitDistance;
};

PrimeShadowTrace primeTraceShadow(
        inout PrimeIntegratorContext context,
        float3 physicalPosition,
        float3 normal,
        LightSample light,
        PrimeRcVolumeStack volumeStack) {
    uint startingMediumCount = min(
            volumeStack.count, PRIME_RC_MAX_VOLUME_STACK_SIZE);
    float3 startingExtinction0 = startingMediumCount > 0u
            ? primeShadowCanonicalExtinction(
                    volumeStack.values[0].extinction)
            : float3(0.0);
    float3 startingExtinction1 = startingMediumCount > 1u
            ? primeShadowCanonicalExtinction(
                    volumeStack.values[1].extinction)
            : float3(0.0);
    primeShadowPayload.opticalDepthMomentHitDistance = float4(0.0);
    primeShadowPayload.terminalExtinctionRayDistance = float4(
            float3(0.0),
            light.distance);
    primeShadowPayload.startingExtinction0Winding = float4(
            startingExtinction0, startingMediumCount > 0u ? 1.0 : 0.0);
    primeShadowPayload.startingExtinction1Winding = float4(
            startingExtinction1, startingMediumCount > 1u ? 1.0 : 0.0);
    primeShadowPayload.startingMediumCount = startingMediumCount;
    float3 traceOrigin = primeOffsetRayOrigin(physicalPosition, normal, light.direction);
    traceRayEXT(
            primeScene,
            gl_RayFlagsNoneEXT,
            0xff,
            3,
            1,
            1,
            traceOrigin,
            0.001,
            light.direction,
            light.distance,
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

PrimeDirectLightingSplit primeEvaluateVisibleDirectSplit(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 shadingNormal,
        LightSample light,
        float3 lightRadiance,
        PrimeRcVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    PrimeDirectLightingSplit result;
    result.diffuse = float3(0.0);
    result.specular = float3(0.0);
    result.direction = light.direction;
    bool transmissive = primeMaterialIsTransmissive(surface.materialControl);
    bool foliage = primeMaterialIsFoliage(surface.materialControl);
    float cosine = transmissive || foliage
            ? abs(dot(shadingNormal, light.direction))
            : max(dot(shadingNormal, light.direction), 0.0);
    if (cosine <= 0.0 || light.pdf <= 0.0
            || all(lightRadiance <= float3(0.0))) {
        return result;
    }
    if (transmissive) {
        float3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
        bool reflection = dot(outwardNormal, viewDirection)
                * dot(outwardNormal, light.direction) >= 0.0;
        BsdfEvaluation evaluation;
        if (conditionalTransparentBranch) {
            // The first visible interface exposes conditional checkerboard proposals. Smooth
            // transmission remains discrete; a rough stained-glass texel has a finite conditional
            // transmission lobe and therefore participates in NEE from the opposite hemisphere.
            evaluation = reflection
                    ? primeEvaluateMinecraftTransparentReflection(
                            surface.baseColor,
                            primeSurfaceOpacity(surface),
                            outwardNormal,
                            surface.materialControl,
                            surface.roughness,
                            surface.opticalControl,
                            viewDirection,
                            light.direction,
                            0.0,
                            volumeStack,
                            surface.adjacentBaseColor,
                            surface.adjacentInterfaceControl)
                    : primeEvaluateMinecraftTransparentTransmission(
                            surface.baseColor,
                            primeSurfaceOpacity(surface),
                            outwardNormal,
                            surface.materialControl,
                            surface.roughness,
                            surface.opticalControl,
                            viewDirection,
                            light.direction,
                            0.0,
                            volumeStack,
                            surface.adjacentBaseColor,
                            surface.adjacentInterfaceControl);
        } else {
            PrimeRcState state = primeMinecraftBoundaryTransmissionState(
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
            evaluation = primeEvaluateMinecraftTransmissionCompleteFromState(
                    state,
                    surface.baseColor,
                    primeSurfaceOpacity(surface),
                    surface.materialControl,
                    viewDirection,
                    light.direction);
        }
        if (conditionalTransparentBranch) {
            // The competing continuation first selects this disjoint hemisphere with probability
            // 1/2, then samples its conditional proposal. MIS must use that complete technique PDF.
            evaluation.pdf = primeTransparentCheckerboardPdf(evaluation.pdf);
        }
        float weightedInversePdf = primePowerHeuristicOverPdf(
                context.diagnostics, light.pdf, evaluation.pdf);
        float3 contribution = primeTripleProduct(
                lightRadiance, evaluation.response, weightedInversePdf);
        if (reflection) {
            result.specular = contribution;
        } else {
            result.diffuse = contribution;
        }
        return result;
    }
    if (foliage) {
        PrimeBsdfComponents components = primeEvaluateMinecraftFoliageComponents(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                light.direction,
                0.0,
                volumeStack);
        float weightedInversePdf = primePowerHeuristicOverPdf(
                context.diagnostics, light.pdf, components.pdf);
        result.diffuse = primeTripleProduct(
                lightRadiance, components.diffuseResponse, weightedInversePdf);
        result.specular = primeTripleProduct(
                lightRadiance, components.specularResponse, weightedInversePdf);
        return result;
    }
    // The translation layer supplies conservative defaults when no community material data is
    // authored. Material provenance must not select a second BSDF: every ordinary opaque surface
    // uses RoboCute's white-furnace-tested BasicMetallic composition.
    PrimeBsdfComponents components = primeEvaluateOpaqueComponents(
            surface.baseColor,
            shadingNormal,
            surface.roughness,
            surface.opticalControl,
            surface.materialControl,
            viewDirection,
            light.direction,
            0.0,
            volumeStack);
    float weightedInversePdf = primePowerHeuristicOverPdf(
            context.diagnostics, light.pdf, components.pdf);
    result.diffuse = primeTripleProduct(
            lightRadiance, components.diffuseResponse, weightedInversePdf);
    result.specular = primeTripleProduct(
            lightRadiance, components.specularResponse, weightedInversePdf);
    return result;
}

bool primeDirectSampleEligible(
        SurfaceInteraction surface,
        float3 shadingNormal,
        LightSample light) {
    return light.pdf > 0.0
            && (primeMaterialIsTransmissive(surface.materialControl)
                    || primeMaterialIsFoliage(surface.materialControl)
                    || dot(shadingNormal, light.direction) > 0.0);
}

struct PrimePrimarySunSample {
    PrimeDirectLightingSplit lighting;
    float penumbra;
    float visibility;
};

float primeSigmaPackDirectionalPenumbra(float distanceToOccluder) {
    distanceToOccluder = primeNrdSanitizeHitDistance(distanceToOccluder);
    if (distanceToOccluder >= PRIME_NRD_FP16_MAX) {
        return PRIME_NRD_FP16_MAX;
    }
    float penumbraRadius = 0.5 * distanceToOccluder
            * tan(ATM_SUN_ANGULAR_RADIUS_RADIANS);
    return min(max(penumbraRadius, 0.0), 32768.0);
}

PrimePrimarySunSample primeEstimatePrimaryDirectSun(
        inout PrimeIntegratorContext context,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 sampleValue,
        PrimeRcVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    PrimePrimarySunSample result;
    result.lighting.diffuse = float3(0.0);
    result.lighting.specular = float3(0.0);
    result.lighting.direction = float3(0.0);
    result.penumbra = 0.0;
    result.visibility = 0.0;
    LightSample light = primeSampleSun(integrator, surface.position, sampleValue);
    if (!primeAtmosphereDistantDirectionVisible(light.direction)) {
        return result;
    }
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, light)) {
        return result;
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            context, surface.position, surface.geometricNormal, light, volumeStack);
    result.penumbra = primeSigmaPackDirectionalPenumbra(shadow.hitDistance);
    result.visibility = float(shadow.hitDistance >= PRIME_NRD_FP16_MAX);
    float3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, light) * shadow.transmittance;
    result.lighting = primeEvaluateVisibleDirectSplit(
            context,
            surface,
            viewDirection,
            shadingNormal,
            light,
            radiance,
            volumeStack,
            conditionalTransparentBranch);
    return result;
}

PrimeDirectLightingSplit primeEstimatePrimaryDirectAreaLight(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 treeSample,
        float2 positionSample,
        PrimeRcVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    AreaLightSample area = primeSampleAreaLight(
            context.diagnostics,
            surface.position,
            primeAreaLightReceiverNormal(surface, viewDirection),
            treeSample,
            positionSample);
    if (!primeDirectSampleEligible(surface, shadingNormal, area.light)) {
        PrimeDirectLightingSplit result;
        result.diffuse = float3(0.0);
        result.specular = float3(0.0);
        result.direction = float3(0.0);
        return result;
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            context, surface.position, surface.geometricNormal, area.light, volumeStack);
    if (shadow.hitDistance < PRIME_NRD_FP16_MAX) {
        PrimeDirectLightingSplit result;
        result.diffuse = float3(0.0);
        result.specular = float3(0.0);
        result.direction = float3(0.0);
        return result;
    }
    float3 radiance =
            primeResolveSampledAreaLightRadiance(area) * shadow.transmittance;
    return primeEvaluateVisibleDirectSplit(
            context,
            surface,
            viewDirection,
            shadingNormal,
            area.light,
            radiance,
            volumeStack,
            conditionalTransparentBranch);
}

float3 primeEstimateDirectAreaLight(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 treeSample,
        float2 positionSample,
        PrimeRcVolumeStack volumeStack) {
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    AreaLightSample area = primeSampleAreaLight(
            context.diagnostics,
            surface.position,
            primeAreaLightReceiverNormal(surface, viewDirection),
            treeSample,
            positionSample);
    if (!primeDirectSampleEligible(surface, shadingNormal, area.light)) {
        return float3(0.0);
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            context, surface.position, surface.geometricNormal, area.light, volumeStack);
    if (shadow.hitDistance < PRIME_NRD_FP16_MAX) {
        return float3(0.0);
    }
    float3 radiance =
            primeResolveSampledAreaLightRadiance(area) * shadow.transmittance;
    PrimeDirectLightingSplit split = primeEvaluateVisibleDirectSplit(
            context,
            surface,
            viewDirection,
            shadingNormal,
            area.light,
            radiance,
            volumeStack,
            false);
    return split.diffuse + split.specular;
}

float3 primeEstimateDirectSun(
        inout PrimeIntegratorContext context,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection,
        float2 sampleValue,
        PrimeRcVolumeStack volumeStack) {
    LightSample light = primeSampleSun(integrator, surface.position, sampleValue);
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
    PrimeDirectLightingSplit split = primeEvaluateVisibleDirectSplit(
            context,
            surface,
            viewDirection,
            shadingNormal,
            light,
            radiance,
            volumeStack,
            false);
    return split.diffuse + split.specular;
}

PrimeDirectLightingSplit primeEstimatePrimaryNonsunDirect(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeRcVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    // Stars are evaluated only when the continuation ray misses. Primary non-sun guides therefore
    // contain only the explicitly sampled area-light family.
    return primeEstimatePrimaryDirectAreaLight(
            context,
            surface,
            viewDirection,
            primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY).xy,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_SECONDARY),
            volumeStack,
            conditionalTransparentBranch);
}

float3 primeEstimateDirectLighting(
        inout PrimeIntegratorContext context,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeRcVolumeStack volumeStack) {
    float3 result = primeEstimateDirectSun(
            context,
            integrator,
            surface,
            viewDirection,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            volumeStack);
#if defined(PRIME_DEFER_SECONDARY_AREA_NEE)
    // The independent Area stage consumes the persisted hit with this exact pre-scatter path.
#else
    result += primeEstimateDirectAreaLight(
            context,
            surface,
            viewDirection,
            primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY).xy,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_SECONDARY),
            volumeStack);
#endif
    return result;
}

bool primeRussianRoulette(
        inout PrimeIntegratorContext context,
        inout PathState path,
        float sampleValue) {
    float survival = primeRussianRouletteSurvival(
            path.throughput, path.etaScale);
    primeRecordUnit(context.diagnostics, survival);
    if (sampleValue >= survival) {
        return false;
    }
    path.throughput = primeRussianRouletteReweight(path.throughput, survival);
    primeRecordNonnegative(context.diagnostics, path.throughput);
    return true;
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

// Renderer-specific integrators compose these terminal estimators. Their caller supplies whether
// the competing Area technique existed, so realtime and offline can keep independent NEE rules.
float3 primeEvaluateEnvironmentContribution(
        inout PrimeIntegratorContext context,
        PathState path, IntegratorRecord integrator) {
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_EMISSION, path.bounce);
    float3 stars = primeStarmapRadiance(
            integrator, path.physicalOrigin, path.rayDirection);
    LightEvaluation sun = primeEvaluateSun(
            integrator, path.physicalOrigin, path.rayDirection);
    float sunWeight = primePreviousCannotUseMis(path)
            ? 1.0
            : primePowerHeuristic(
                    context.diagnostics, path.previousBsdfPdf, sun.pdf);
    return path.throughput
            * (primeEnvironmentRadiance(integrator, path.rayDirection)
            + stars
            + sun.radiance * sunWeight);
}

float3 primeEvaluateLocalHitEmission(
        inout PrimeIntegratorContext context,
        PathState path,
        SurfaceInteraction surface,
        bool previousUsedAreaNee) {
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_EMISSION, path.bounce);
    if ((surface.materialControl & PRIME_MATERIAL_VISIBLE_EMISSION) != 0u) {
        return surface.baseColor
                * PRIME_LEVEL_15_BLOCK_INTENSITY
                * primeBlockLightRadianceMultiplier();
    }
    // Reconstruct the two-level reverse tree PDF only when the previous renderer-specific
    // transport vertex actually had a competing Area NEE technique.
    bool evaluateAreaPdf = previousUsedAreaNee
            && !primePreviousCannotUseMis(path);
    LightEvaluation hitAreaLight = primeEvaluateAreaLight(
            context.diagnostics,
            surface,
            path.physicalOrigin,
            path.rayDirection,
            path.previousLightNormal,
            evaluateAreaPdf);
    float hitAreaWeight = primeAreaHitMisWeightValue(
            previousUsedAreaNee,
            !evaluateAreaPdf,
            path.previousBsdfPdf,
            hitAreaLight.pdf);
    return hitAreaLight.radiance * hitAreaWeight;
}

float3 primeEvaluateHitEmission(
        inout PrimeIntegratorContext context,
        PathState path,
        SurfaceInteraction surface,
        bool previousUsedAreaNee) {
    return path.throughput * primeEvaluateLocalHitEmission(
            context, path, surface, previousUsedAreaNee);
}

bool primeApplySegmentMedium(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        PrimeRcVolumeStack volumeStack) {
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_MEDIUM, path.bounce);
    if (volumeStack.count == 0u) {
        return true;
    }
    PrimeRcVolume medium = volumeStack.values[volumeStack.count - 1u];
    path.throughput *= primeSegmentTransmittance(medium.extinction, surface.t);
    primeRecordNonnegative(context.diagnostics, path.throughput);
    return !all(path.throughput == float3(0.0));
}

struct PrimePathScatter {
    BsdfSample bsdf;
    PrimeRcVolumeStack volumeStack;
};

PrimePathScatter primeSamplePathSurfaceWithMinimumRoughness(
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeRcVolumeStack volumeStack,
        float minimumLinearRoughness) {
    PrimePathScatter result;
    result.volumeStack = volumeStack;
    if (primeMaterialIsFoliage(surface.materialControl)) {
        PrimeRcState state = primeMinecraftFoliageStateWithMinimumRoughness(
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
                state, viewDirection, sampleValue, volumeStack);
    } else if (primeMaterialIsTransmissive(surface.materialControl)) {
        float3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
        PrimeRcState state = primeMinecraftBoundaryTransmissionStateWithMinimumRoughness(
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
                        volumeStack);
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
        PrimeRcState state = primeOpaqueStateWithMinimumRoughness(
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
                volumeStack);
    }
    return result;
}

PrimePathScatter primeSamplePathSurface(
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeRcVolumeStack volumeStack) {
    return primeSamplePathSurfaceWithMinimumRoughness(
            surface, viewDirection, sampleValue, volumeStack, 0.0);
}

void primeSampleGuidedPathSurface(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        float3 sampleValue,
        PrimeRcVolumeStack volumeStack,
        uint bounce,
        out PrimePathScatter scatter,
        out PrimeDenoiseAlbedos albedos) {
    scatter.volumeStack = volumeStack;
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_SURFACE, bounce);
    if (primeMaterialIsFoliage(surface.materialControl)) {
        PrimeRcState state = primeMinecraftFoliageState(
                surface.baseColor,
                primeSurfaceShadingNormal(surface, viewDirection),
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                surface.t,
                volumeStack);
        albedos = primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_FOLIAGE);
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, bounce);
        scatter.bsdf = primeSampleMinecraftFoliageFromState(
                state, viewDirection, sampleValue, volumeStack);
    } else if (primeMaterialIsTransmissive(surface.materialControl)) {
        float3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
        PrimeRcState state = primeMinecraftBoundaryTransmissionState(
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
        albedos = primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_TRANSMISSIVE);
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, bounce);
        PrimeTransmissiveBsdfSample sampled =
                primeSampleMinecraftTransmissionCompleteFromState(
                        state,
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        surface.materialControl,
                        viewDirection,
                        sampleValue,
                        volumeStack);
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
        scatter.bsdf = sampled.bsdfSample;
        scatter.volumeStack = sampled.volumeStack;
    } else {
        float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
        PrimeRcState state = primeOpaqueState(
                surface.baseColor,
                shadingNormal,
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                surface.t,
                volumeStack);
        albedos = primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_OPAQUE);
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, bounce);
        scatter.bsdf = primeSampleOpaqueFromState(
                state,
                shadingNormal,
                viewDirection,
                sampleValue,
                volumeStack);
    }
}

bool primeHasScatter(BsdfSample bsdf) {
    return bsdf.eventFlags != 0u;
}

bool primeIsDiscreteSample(BsdfSample bsdf) {
    return (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u;
}

bool primeIsSolidAngleSample(BsdfSample bsdf) {
    uint solidAngleFlags = PRIME_BSDF_EVENT_DIFFUSE | PRIME_BSDF_EVENT_GLOSSY;
    return (bsdf.eventFlags & solidAngleFlags) != 0u && !primeIsDiscreteSample(bsdf);
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

#if defined(PRIME_SHARC_QUERY)
bool primeQuerySharc(
        inout PrimeIntegratorContext context,
        PathState path,
        SurfaceInteraction surface,
        bool previousUsedAreaNee,
        out float3 contribution) {
    contribution = float3(0.0);
    bool diagnosticSample = primeSharcDiagnosticSample(path);
    primeRecordSharcDiagnostic(
            diagnosticSample, PRIME_SHARC_DIAGNOSTIC_QUERY);
    if (path.previousSharcEvent == PRIME_SHARC_EVENT_DISCRETE) {
        primeRecordSharcDiagnostic(
                diagnosticSample, PRIME_SHARC_DIAGNOSTIC_DISCRETE_SKIP);
        return false;
    }

    float voxelSize = primeSharcVoxelSize(
            surface.position, surface.geometricNormal);
    const float voxelDiagonalScale = 1.7320508075688772;
    if (surface.t <= voxelSize * voxelDiagonalScale) {
        primeRecordSharcDiagnostic(
                diagnosticSample, PRIME_SHARC_DIAGNOSTIC_SHORT_SKIP);
        return false;
    }
    if (path.previousSharcEvent == PRIME_SHARC_EVENT_GLOSSY) {
        float roughness = min(path.previousSharcRoughness, 0.99);
        float alpha = roughness * roughness;
        float alphaSquared = alpha * alpha;
        float footprintRadius = surface.t * sqrt(
                0.5 * alphaSquared / max(1.0 - alphaSquared, 1.0e-6));
        if (footprintRadius <= voxelSize) {
            primeRecordSharcDiagnostic(
                    diagnosticSample, PRIME_SHARC_DIAGNOSTIC_GLOSSY_SKIP);
            return false;
        }
    }

    float directionWeight = path.previousSharcEvent == PRIME_SHARC_EVENT_GLOSSY
            ? 1.0 - path.previousSharcRoughness
            : 0.0;
    SharcHitData hit = primeSharcHitData(
            surface.position,
            surface.geometricNormal,
            -path.rayDirection,
            directionWeight,
            primeSharcMaterialDemodulation(surface),
            primeEvaluateLocalHitEmission(
                    context, path, surface, previousUsedAreaNee));
    float3 radiance;
    primeRecordSharcDiagnostic(
            diagnosticSample, PRIME_SHARC_DIAGNOSTIC_LOOKUP);
    if (!SharcGetCachedRadiance(
            primeSharcParameters(), hit, radiance, false)) {
        return false;
    }
    primeRecordSharcDiagnostic(
            diagnosticSample, PRIME_SHARC_DIAGNOSTIC_HIT);
    contribution = path.throughput * radiance;
    return true;
}
#endif

bool primeAdvancePath(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        PrimePreparedSampleBase bounceSample,
        bool discreteOnlyInterface,
        float surfaceLinearRoughness) {
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_PATH_ADVANCE, path.bounce);
    float3 nextThroughput = primeProductOver(path.throughput, bsdf.response, bsdf.pdf);
    primeRecordNonnegative(context.diagnostics, nextThroughput);
    if (all(nextThroughput == float3(0.0))) {
        return false;
    }
    uint previousLightNormal = discreteOnlyInterface
            ? 0u
            : primeAreaLightReceiverNormal(surface, -path.rayDirection);
    path.throughput = nextThroughput;
    path.physicalOrigin = surface.position;
    path.traceOrigin = primeOffsetRayOrigin(
            path.physicalOrigin, surface.geometricNormal, bsdf.direction);
    path.rayDirection = bsdf.direction;
    path.previousBsdfPdf = bsdf.pdf;
    path.previousLightNormal = previousLightNormal;
    path.previousSharcRoughness = surfaceLinearRoughness;
    path.previousSharcEvent = primeSharcEvent(bsdf);
    path.flags = (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u
            ? PRIME_PATH_PREVIOUS_DISCRETE
            : 0u;
    path.etaScale = primeEtaScaleAfterScatter(
            path.etaScale,
            bsdf.relativeEta,
            (bsdf.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u);
    primeRecordNonnegative(context.diagnostics, path.etaScale);
    // bounce counts every completed scatter, including discrete-only transparent interfaces.
    if (!primeRussianRouletteApplies(
            path.bounce, PRIME_RUSSIAN_ROULETTE_START)) {
        return true;
    }
    float rouletteSample = primeHashSample1D(
            bounceSample,
            PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    return primeRussianRoulette(context, path, rouletteSample);
}

// Queue-resident PSR state stores the bounded discrete chain as an incremental path length and
// orthogonal direction transform. The last discrete position is exactly path.physicalOrigin at every
// queue boundary, so keeping a second float3 would increase both slot size and live registers.

void primeAppendQueuedPsrDiscrete(
        inout PrimeQueuedPsrState state,
        float3 previousPosition,
        SurfaceInteraction surface,
        BsdfSample bsdf) {
    primeAppendQueuedPsrState(
            state,
            primePush.cameraPosition,
            previousPosition,
            surface.position,
            surface.geometricNormal,
            (bsdf.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u);
}

float primeQueuedPsrAnchorDistance(
        PrimeQueuedPsrState state,
        float3 target,
        float3 targetNormal) {
    return primeQueuedPsrAnchorDistanceValue(
            state,
            primePush.cameraPosition,
            target,
            targetNormal);
}

void primeSetQueuedPsrGuide(
        inout PrimeDenoiserGuides guides,
        SurfaceInteraction surface,
        float3 shadingNormal,
        float linearRoughness,
        PrimeDenoiseAlbedos albedos,
        float3 guideThroughput,
        float3 lastPosition,
        PrimeQueuedPsrState state) {
    float3 position = surface.position - primePush.cameraPosition;
    float3 normal = shadingNormal;
    float3 virtualPosition;
    float3 virtualNormal;
    if (primeBuildQueuedPsrGuideValue(
            state,
            lastPosition,
            surface.position,
            normal,
            virtualPosition,
            virtualNormal)) {
        position = virtualPosition;
        normal = virtualNormal;
    }
    guides.primaryDistance = length(position);
    guides.primaryPosition = position;
    guides.primaryPreviousPosition = position;
    guides.primaryHasMotion = false;
    guides.primaryAlbedo = guideThroughput * albedos.diffuse;
    guides.primaryNormal = normal;
    guides.primaryHitKind = surface.hitKind;
    guides.primaryMaterialFlags = primeNrdGuideControl(
            surface.materialControl, surface.opticalControl);
    guides.primarySpecularAlbedo = guideThroughput * albedos.specular;
    guides.primaryLinearRoughness = linearRoughness;
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

uint primeTransparentGuideMode() {
    return (primePush.path.z >> PRIME_PATH_TRANSPARENT_GUIDE_MODE_SHIFT)
            & PRIME_PATH_TRANSPARENT_GUIDE_MODE_MASK;
}

// Advances the checkerboard-selected primary-transparent branch by one queued vertex. Its signal
// lane remains branch-owned while traversal/SER sorts the sole active continuation with every
// other path.
bool primeIntegrateTransparentWavefrontSurface(
        inout PrimeIntegratorContext context,
        inout PathState path,
        IntegratorRecord integrator,
        inout PrimeRcVolumeStack volumeStack,
        inout PrimeTransparentBranchResult result,
        inout PrimeQueuedPsrState psrState,
        inout bool hasGuide,
        inout bool diffusePath,
        inout uint guideBounce,
        inout bool directionalGuide,
        SurfaceInteraction surface,
        bool transmissionBranch,
        bool guideEnabled,
        uint pathIndex) {
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_SURFACE, path.bounce);
    primeRecordNonFinite(context.diagnostics, surface.position);
    primeRecordNonnegative(context.diagnostics, surface.t);
    primeRecordDirection(context.diagnostics, surface.geometricNormal);
    primeRecordUnit(context.diagnostics, surface.baseColor);
    if (!primeKnownHitKind(surface)) {
        return false;
    }

    float hitDistance = surface.hitKind == PRIME_HIT_NONE
            ? PRIME_NRD_FP16_MAX
            : primeNrdSanitizeHitDistance(surface.t);
    if (path.bounce == 1u) {
        result.firstHitDistance = hitDistance;
    }
    if (hasGuide && path.bounce == guideBounce + 1u) {
        if (diffusePath) {
            result.guides.diffuseHitDistance = hitDistance;
        } else {
            result.guides.specularHitDistance = hitDistance;
        }
    }
    if (surface.hitKind == PRIME_HIT_NONE) {
        if (guideEnabled && !transmissionBranch && !hasGuide
                && primeQueuedPsrCount(psrState) > 0u
                && !primeQueuedPsrOverflowed(psrState)) {
            float3 direction =
                    primeQueuedPsrVirtualDirection(psrState, path.rayDirection);
            float lengthSquared = dot(direction, direction);
            if (primeNrdIsFinite(direction) && primeNrdIsFinite(lengthSquared)
                    && lengthSquared > 1.0e-12) {
                result.guides.primaryPosition =
                        direction * rsqrt(lengthSquared);
                directionalGuide = true;
            }
        }
        primeAccumulateTransparentBranch(
                context,
                result,
                guideEnabled ? diffusePath : transmissionBranch,
                primeEvaluateEnvironmentContribution(context, path, integrator));
        return false;
    }
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return false;
    }

    float3 viewDirection = -path.rayDirection;
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    bool transmissive = primeMaterialIsTransmissive(surface.materialControl);
    float surfaceLinearRoughness = (transmissive || !hasGuide)
            ? primeSurfaceLinearRoughness(surface)
            : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    bool supportsSolidAngle = primeClosureSupports(
            closureTraits, PRIME_MEASURE_SOLID_ANGLE);
    bool discreteOnlyInterface = primeClosureIsDiscreteOnly(closureTraits);
    bool primarySurfaceReplacement =
            guideEnabled && !hasGuide && !discreteOnlyInterface;
    bool contributionDiffuse = guideEnabled
            ? (primarySurfaceReplacement || diffusePath)
            : transmissionBranch;
    bool previousUsedAreaNee = path.bounce > 0u;
    float3 emitted = primeEvaluateHitEmission(
            context, path, surface, previousUsedAreaNee);
    primeAccumulateTransparentBranch(
            context, result, contributionDiffuse, emitted);

    if (supportsSolidAngle) {
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
        if (primarySurfaceReplacement) {
            PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
                    context,
                    integrator,
                    surface,
                    viewDirection,
                    primeSobolSample2D(
                            preparedSample,
                            PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                            PRIME_SAMPLE_DIMENSION_PRIMARY),
                    volumeStack,
                    false);
            float3 diffuseDirect =
                    path.throughput * sun.lighting.diffuse * sun.visibility;
            float3 specularDirect =
                    path.throughput * sun.lighting.specular * sun.visibility;
            PrimeDirectLightingSplit nonsun = primeEstimatePrimaryNonsunDirect(
                    context,
                    surface,
                    viewDirection,
                    preparedSample,
                    volumeStack,
                    false);
            diffuseDirect += path.throughput * nonsun.diffuse;
            specularDirect += path.throughput * nonsun.specular;
            result.guides.primaryAreaDiffuse =
                    path.throughput * nonsun.diffuse;
            result.guides.primaryAreaSpecular =
                    path.throughput * nonsun.specular;
            result.guides.primaryAreaDirection = nonsun.direction;
            primeAccumulate(context, result.diffuseRadiance, diffuseDirect);
            primeAccumulate(context, result.specularRadiance, specularDirect);
        } else {
            float3 direct = path.throughput * primeEstimateDirectLighting(
                    context,
                    integrator,
                    surface,
                    viewDirection,
                    preparedSample,
                    volumeStack);
            primeAccumulateTransparentBranch(
                    context,
                    result,
                    guideEnabled ? diffusePath : transmissionBranch,
                    direct);
        }
    }

    float3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimePathScatter scatter;
    PrimeDenoiseAlbedos surfaceAlbedos;
    surfaceAlbedos.diffuse = float3(0.0);
    surfaceAlbedos.specular = float3(0.0);
    if (primarySurfaceReplacement) {
        primeSampleGuidedPathSurface(
                context,
                surface,
                viewDirection,
                scatterSample,
                volumeStack,
                path.bounce,
                scatter,
                surfaceAlbedos);
        primeSetQueuedPsrGuide(
                result.guides,
                surface,
                primeSurfaceShadingNormal(surface, viewDirection),
                surfaceLinearRoughness,
                surfaceAlbedos,
                path.throughput,
                path.physicalOrigin,
                psrState);
        if (transmissionBranch) {
            result.anchorDistance = primeQueuedPsrAnchorDistance(
                    psrState, surface.position, surface.geometricNormal);
        }
        hasGuide = true;
        guideBounce = path.bounce;
    } else {
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
        scatter = primeSamplePathSurface(
                surface, viewDirection, scatterSample, volumeStack);
    }
    BsdfSample bsdf = scatter.bsdf;
    if (!primeHasScatter(bsdf)) {
        return false;
    }
    primeRecordDirection(context.diagnostics, bsdf.direction);
    primeRecordNonnegative(context.diagnostics, bsdf.response);
    primeRecordNonnegative(context.diagnostics, bsdf.pdf);
    primeRecordNonnegative(context.diagnostics, bsdf.relativeEta);
    if (primarySurfaceReplacement) {
        diffusePath = (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
        float3 virtualDirection =
                primeQueuedPsrVirtualDirection(psrState, bsdf.direction);
        if (diffusePath) {
            result.guides.diffuseDirection = virtualDirection;
        } else {
            result.guides.specularDirection = virtualDirection;
        }
    } else if (guideEnabled && !hasGuide && discreteOnlyInterface
            && (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u) {
        primeAppendQueuedPsrDiscrete(
                psrState, path.physicalOrigin, surface, bsdf);
    }
    volumeStack = scatter.volumeStack;
    if (!primeAdvancePath(
            context,
            path,
            surface,
            bsdf,
            preparedSample,
            discreteOnlyInterface,
            surfaceLinearRoughness)) {
        return false;
    }
    path.bounce++;
    return true;
}

PrimeIntegrationResult primeEmptyIntegrationResult() {
    PrimeIntegrationResult result;
    result.radiance.diffuse = float3(0.0);
    result.radiance.specular = float3(0.0);
    result.radiance.stable = float3(0.0);
    result.radiance.unshadowedSun = float3(0.0);
    result.radiance.sunVisibility = 0.0;
    result.guides = primeEmptyDenoiserGuides();
    result.reflectionDiffuseRadiance = float3(0.0);
    result.reflectionSpecularRadiance = float3(0.0);
    result.transmissionGuides = primeEmptyDenoiserGuides();
    result.reflectionGuides = primeEmptyDenoiserGuides();
    result.transmissionAnchorDistance = -1.0;
    result.reflectionDirectionalGuide = false;
    result.transparentPrimary = false;
    return result;
}

PrimeDenoiserState primeInitialDenoiserState() {
    PrimeDenoiserState state;
    state.hasPrimarySurface = false;
    state.reachedSolidAngle = false;
    state.diffuseAlbedoProduct = float3(1.0);
    state.specularAlbedoProduct = float3(0.0);
    state.diffusePath = false;
    state.primaryBounce = 0u;
    return state;
}

// Processes exactly one ordinary realtime path vertex. Primary transparent interfaces remain a
// local island because two reconstruction records share one output pixel, although only the
// checkerboard-selected record carries transport. Every other continuation can cross this
// boundary without carrying invocation-local BSDF state.
bool primeIntegrateWavefrontSurface(
        inout PrimeIntegratorContext context,
        inout PathState path,
        IntegratorRecord integrator,
        inout PrimeRcVolumeStack volumeStack,
        inout PrimeDenoiserState denoiserState,
        inout PrimeIntegrationResult result,
        SurfaceInteraction surface,
        uint pathIndex) {
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_SURFACE, path.bounce);
    primeRecordNonFinite(context.diagnostics, surface.position);
    primeRecordNonnegative(context.diagnostics, surface.t);
    primeRecordDirection(context.diagnostics, surface.geometricNormal);
    primeRecordUnit(context.diagnostics, surface.baseColor);
    if (!primeKnownHitKind(surface)) {
        return false;
    }

    if (denoiserState.hasPrimarySurface
            && path.bounce == denoiserState.primaryBounce + 1u) {
        float firstBounceHitDistance = surface.hitKind == PRIME_HIT_NONE
                ? PRIME_NRD_FP16_MAX
                : primeNrdSanitizeHitDistance(surface.t);
        if (denoiserState.diffusePath) {
            result.guides.diffuseHitDistance = firstBounceHitDistance;
        } else {
            result.guides.specularHitDistance = firstBounceHitDistance;
        }
    }
    if (surface.hitKind == PRIME_HIT_NONE) {
        float3 contribution = primeEvaluateEnvironmentContribution(
                context, path, integrator);
        if (!denoiserState.hasPrimarySurface) {
            primeAccumulate(context, result.radiance.stable, contribution);
        } else {
            primeAccumulateAfterPrimary(
                    context, result, denoiserState.diffusePath, contribution);
        }
        return false;
    }
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return false;
    }


#if defined(PRIME_SHARC_QUERY)
    float3 sharcContribution;
    if (denoiserState.hasPrimarySurface
            && primeQuerySharc(
                    context,
                    path,
                    surface,
                    denoiserState.hasPrimarySurface,
                    sharcContribution)) {
        primeAccumulateAfterPrimary(
                context,
                result,
                denoiserState.diffusePath,
                sharcContribution);
        return false;
    }
#endif

    float3 viewDirection = -path.rayDirection;
    bool previousUsedAreaNee = denoiserState.hasPrimarySurface;
    float3 emitted = primeEvaluateHitEmission(
            context, path, surface, previousUsedAreaNee);
    if (!denoiserState.hasPrimarySurface) {
        primeAccumulate(context, result.radiance.stable, emitted);
    } else {
        primeAccumulateAfterPrimary(
                context, result, denoiserState.diffusePath, emitted);
    }

    bool transmissive = primeMaterialIsTransmissive(surface.materialControl);
    float surfaceLinearRoughness =
            (transmissive || !denoiserState.reachedSolidAngle)
            ? primeSurfaceLinearRoughness(surface)
            : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    bool supportsSolidAngle = primeClosureSupports(
            closureTraits, PRIME_MEASURE_SOLID_ANGLE);
    bool discreteOnlyInterface = primeClosureIsDiscreteOnly(closureTraits);
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    if (supportsSolidAngle) {
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
        if (!denoiserState.hasPrimarySurface) {
            PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
                    context,
                    integrator,
                    surface,
                    viewDirection,
                    primeSobolSample2D(
                            preparedSample,
                            PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                            PRIME_SAMPLE_DIMENSION_PRIMARY),
                    volumeStack,
                    false);
            result.guides.sunPenumbra = sun.penumbra;
            result.radiance.sunVisibility = sun.visibility;
            primeAccumulate(
                    context,
                    result.radiance.unshadowedSun,
                    path.throughput * (sun.lighting.diffuse + sun.lighting.specular));
            PrimeDirectLightingSplit nonsun = primeEstimatePrimaryNonsunDirect(
                    context,
                    surface,
                    viewDirection,
                    preparedSample,
                    volumeStack,
                    false);
            float3 primaryAreaDiffuse = path.throughput * nonsun.diffuse;
            float3 primaryAreaSpecular = path.throughput * nonsun.specular;
            primeAccumulate(context, result.radiance.diffuse, primaryAreaDiffuse);
            primeAccumulate(context, result.radiance.specular, primaryAreaSpecular);
            result.guides.primaryAreaDiffuse = primaryAreaDiffuse;
            result.guides.primaryAreaSpecular = primaryAreaSpecular;
            if (any(primaryAreaDiffuse > float3(0.0))
                    || any(primaryAreaSpecular > float3(0.0))) {
                result.guides.primaryAreaDirection = nonsun.direction;
            }
        } else {
            float3 direct = path.throughput * primeEstimateDirectLighting(
                    context,
                    integrator,
                    surface,
                    viewDirection,
                    preparedSample,
                    volumeStack);
            primeAccumulateAfterPrimary(
                    context,
                    result,
                    denoiserState.diffusePath,
                    direct);
        }
    }

    float3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimePathScatter scatter;
    PrimeDenoiseAlbedos surfaceAlbedos;
    surfaceAlbedos.diffuse = float3(0.0);
    surfaceAlbedos.specular = float3(0.0);
    if (!denoiserState.reachedSolidAngle) {
        primeSampleGuidedPathSurface(
                context,
                surface,
                viewDirection,
                scatterSample,
                volumeStack,
                path.bounce,
                scatter,
                surfaceAlbedos);
    } else {
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
        scatter = primeSamplePathSurface(
                surface, viewDirection, scatterSample, volumeStack);
    }
    BsdfSample bsdf = scatter.bsdf;
    volumeStack = scatter.volumeStack;
    bool hasScatter = primeHasScatter(bsdf);
    if (hasScatter) {
        primeRecordDirection(context.diagnostics, bsdf.direction);
        primeRecordNonnegative(context.diagnostics, bsdf.response);
        primeRecordNonnegative(context.diagnostics, bsdf.pdf);
        primeRecordNonnegative(context.diagnostics, bsdf.relativeEta);
    }
    bool sampledSolidAngle = primeIsSolidAngleSample(bsdf);
    if (!denoiserState.reachedSolidAngle) {
        bool firstDenoiseSurface = !denoiserState.hasPrimarySurface;
        if (firstDenoiseSurface) {
            denoiserState.specularAlbedoProduct += surfaceAlbedos.specular;
            denoiserState.diffuseAlbedoProduct *= surfaceAlbedos.diffuse;
            denoiserState.diffusePath = sampledSolidAngle
                    && (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
            denoiserState.hasPrimarySurface = true;
            denoiserState.primaryBounce = path.bounce;
            result.guides.primaryDistance = length(
                    surface.position - primePush.cameraPosition);
            result.guides.primaryPosition = surface.position - primePush.cameraPosition;
            result.guides.primaryPreviousPosition =
                    primeSurfacePreviousPosition(surface) - primePush.cameraPosition;
            result.guides.primaryHasMotion = primeSurfaceHasMotion(surface);
            result.guides.primaryAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.diffuseAlbedoProduct);
            result.guides.primaryNormal =
                    primeSurfaceShadingNormal(surface, viewDirection);
            result.guides.primaryHitKind = surface.hitKind;
            result.guides.primaryMaterialFlags = primeNrdGuideControl(
                    surface.materialControl, surface.opticalControl);
            result.guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.specularAlbedoProduct);
            result.guides.primaryLinearRoughness = surfaceLinearRoughness;
            if (hasScatter) {
                if (denoiserState.diffusePath) {
                    result.guides.diffuseDirection = bsdf.direction;
                } else {
                    result.guides.specularDirection = bsdf.direction;
                }
            }
        }

        bool surfaceHasSolidAngleLobe = supportsSolidAngle;
        if (sampledSolidAngle || (!hasScatter && surfaceHasSolidAngleLobe)) {
            if (!firstDenoiseSurface) {
                denoiserState.specularAlbedoProduct *=
                        surfaceAlbedos.specular + surfaceAlbedos.diffuse;
            }
            denoiserState.reachedSolidAngle = true;
            result.guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.specularAlbedoProduct);
        } else if (!firstDenoiseSurface && primeIsDiscreteSample(bsdf)) {
            denoiserState.specularAlbedoProduct *= surfaceAlbedos.specular;
            result.guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.specularAlbedoProduct);
        }
    }
    if (!hasScatter
            || !primeAdvancePath(
                    context,
                    path,
                    surface,
                    bsdf,
                    preparedSample,
                    discreteOnlyInterface,
                    surfaceLinearRoughness)) {
        return false;
    }
    path.bounce++;
    return true;
}


#endif
