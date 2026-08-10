#ifndef PRIME_INTEGRATOR_GLSL
#define PRIME_INTEGRATOR_GLSL

#include "queued_psr_math.glsl"
#include "transparent_checkerboard.glsl"

// The entire estimator operates in linear Rec.2020 D65. Scheduling changes may move this state
// between kernels, but they must not reinterpret it as encoded sRGB or another RGB basis.

const uint PRIME_HIT_NONE = 0u;
const uint PRIME_HIT_SURFACE = 1u;
const uint PRIME_PATH_PREVIOUS_DISCRETE = 1u;

struct PrimeDirectLightingSplit {
    vec3 diffuse;
    vec3 specular;
    vec3 direction;
};

// This is the complete Monte Carlo estimate, split only because realtime denoisers consume
// different signal classes. Adding these mutually exclusive partitions reconstructs the beauty
// sample exactly; no guide is allowed to feed back into these values.
struct PrimePathRadiance {
    vec3 diffuse;
    vec3 specular;
    vec3 stable;
    vec3 unshadowedSun;
    float sunVisibility;
};

// Auxiliary observations used by reconstruction. They describe the sampled path and primary
// surface, but are not radiance contributions and therefore cannot change the reference estimate.
struct PrimeDenoiserGuides {
    float primaryDistance;
    float specularHitDistance;
    float diffuseHitDistance;
    float sunPenumbra;
    vec3 primaryAlbedo;
    uint primaryHitKind;
    vec3 primaryNormal;
    uint primaryMaterialFlags;
    vec3 primarySpecularAlbedo;
    float primaryLinearRoughness;
    vec3 primaryPosition;
    vec3 primaryPreviousPosition;
    bool primaryHasMotion;
    vec3 diffuseDirection;
    vec3 specularDirection;
    vec3 primaryAreaDiffuse;
    vec3 primaryAreaSpecular;
    vec3 primaryAreaDirection;
};

struct PrimeIntegrationResult {
    PrimePathRadiance radiance;
    PrimeDenoiserGuides guides;
    vec3 reflectionDiffuseRadiance;
    vec3 reflectionSpecularRadiance;
    PrimeDenoiserGuides transmissionGuides;
    PrimeDenoiserGuides reflectionGuides;
    float transmissionAnchorDistance;
    bool reflectionDirectionalGuide;
    bool transparentPrimary;
};

struct PrimeTransparentBranchResult {
    vec3 diffuseRadiance;
    vec3 specularRadiance;
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
    vec3 diffuseAlbedoProduct;
    vec3 specularAlbedoProduct;
    bool diffusePath;
    uint primaryBounce;
};

vec3 primeResolveIntegrationRadiance(PrimeIntegrationResult result) {
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
    guides.primaryAlbedo = vec3(0.0);
    guides.primaryHitKind = PRIME_HIT_NONE;
    guides.primaryNormal = vec3(0.0, 1.0, 0.0);
    guides.primaryMaterialFlags = 0u;
    guides.primarySpecularAlbedo = vec3(0.0);
    guides.primaryLinearRoughness = PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    guides.primaryPosition = vec3(0.0);
    guides.primaryPreviousPosition = vec3(0.0);
    guides.primaryHasMotion = false;
    guides.diffuseDirection = vec3(0.0);
    guides.specularDirection = vec3(0.0);
    guides.primaryAreaDiffuse = vec3(0.0);
    guides.primaryAreaSpecular = vec3(0.0);
    guides.primaryAreaDirection = vec3(0.0);
    return guides;
}

vec3 primeOffsetRayOrigin(vec3 physicalPosition, vec3 normal, vec3 direction) {
    // The physical shading point remains unchanged for BSDF/light/PDF evaluation. Only the
    // traversal origin receives this offset, preventing a geometric epsilon from changing the
    // estimator or a future area-light solid-angle conversion.
    float side = dot(normal, direction) >= 0.0 ? 1.0 : -1.0;
    return physicalPosition + normal * (side * 0.001);
}

SurfaceInteraction primeTraceSurfaceClassified(
        vec3 origin,
        vec3 direction,
        uint pathClass) {
    primePayload.t = 0.0;
    primePayload.geometricNormal = vec3(0.0, 1.0, 0.0);
    primePayload.hitKind = PRIME_HIT_NONE;
    primePayload.baseColor = vec3(0.0);
    primePayload.materialControl = 0u;
    primePayload.sectionIndex = 0u;
    primePayload.emitterIndex = PRIME_NO_LIGHT_INDEX;
    primePayload.textureLod = 0.0;
    primePayload.opacity = 0.0;
    primePayload.shadingNormal = primePackOctahedralNormal(vec3(0.0, 1.0, 0.0));
    primePayload.roughness = primeDefaultLinearRoughness();
    primePayload.opticalControl = 0u;
    primePayload.adjacentBaseColor = vec3(0.0);
    primePayload.adjacentInterfaceControl = 0u;
#if defined(PRIME_ENABLE_SER) && !defined(PRIME_DISABLE_SER_REORDER)
    // Hit objects decouple traversal from closest-hit/miss execution. The reorder point groups
    // those continuations by shader first and by the low section-index bits second, improving
    // both branch and primitive-buffer locality in incoherent terrain. Keep this helper narrow:
    // every caller-local value live here becomes state that the implementation may need to save
    // and restore across invocation repacking.
    hitObjectEXT hitObject;
    hitObjectRecordEmptyEXT(hitObject);
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
            0);
    // Keep six section bits for primitive-buffer locality and reserve two bits for the caller's
    // transport class. Reordered invocations therefore stay coherent after returning to ordinary,
    // transparent-reflection or transparent-transmission shading.
    uint coherenceHint = ((pathClass & 0x3u) << 6u)
            | (hitObjectIsHitEXT(hitObject)
                    ? uint(hitObjectGetInstanceCustomIndexEXT(hitObject)) & 0x3fu
                    : 0u);
    reorderThreadEXT(hitObject, coherenceHint, 8u);
    hitObjectExecuteShaderEXT(hitObject, 0);
#else
    traceRayEXT(primeScene, gl_RayFlagsNoneEXT, 0xff, 0, 1, 0,
            origin, 0.0, direction, 1000000.0, 0);
#endif
    SurfaceInteraction surface;
    uint packedHitKind = primePayload.hitKind;
    surface.position = (packedHitKind & PRIME_HIT_KIND_MASK) == PRIME_HIT_NONE
            ? vec3(0.0)
            : origin + direction * primePayload.t;
    surface.t = primePayload.t;
    surface.geometricNormal = primePayload.geometricNormal;
    surface.hitKind = packedHitKind & PRIME_HIT_KIND_MASK;
    surface.baseColor = primePayload.baseColor;
    surface.materialControl = primePayload.materialControl;
    surface.sectionIndex = primePayload.sectionIndex;
    surface.emitterIndex = primePayload.emitterIndex;
    surface.textureLod = primePayload.textureLod;
    surface.opacity = primePayload.opacity;
    surface.shadingNormal = primePayload.shadingNormal;
    surface.roughness = primePayload.roughness;
    surface.opticalControl = primePayload.opticalControl;
    surface.motionZFlags = packedHitKind & ~PRIME_HIT_KIND_MASK;
    surface.adjacentBaseColor = primePayload.adjacentBaseColor;
    surface.adjacentInterfaceControl = primePayload.adjacentInterfaceControl;
    return surface;
}

SurfaceInteraction primeTraceSurface(vec3 origin, vec3 direction) {
    return primeTraceSurfaceClassified(origin, direction, 0u);
}

SurfaceInteraction primeTraceSurfaceWithoutReorder(vec3 origin, vec3 direction) {
#if !defined(PRIME_ENABLE_SER)
    return primeTraceSurface(origin, direction);
#else
    // The sparse tail keeps the complete result, medium and denoiser state live across its
    // local loop. Repacking there spills more state than it recovers through coherence, so this
    // entry deliberately executes the same trace without the SER reorder point.
    primePayload.t = 0.0;
    primePayload.geometricNormal = vec3(0.0, 1.0, 0.0);
    primePayload.hitKind = PRIME_HIT_NONE;
    primePayload.baseColor = vec3(0.0);
    primePayload.materialControl = 0u;
    primePayload.sectionIndex = 0u;
    primePayload.emitterIndex = PRIME_NO_LIGHT_INDEX;
    primePayload.textureLod = 0.0;
    primePayload.opacity = 0.0;
    primePayload.shadingNormal =
            primePackOctahedralNormal(vec3(0.0, 1.0, 0.0));
    primePayload.roughness = primeDefaultLinearRoughness();
    primePayload.opticalControl = 0u;
    primePayload.adjacentBaseColor = vec3(0.0);
    primePayload.adjacentInterfaceControl = 0u;
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
    uint packedHitKind = primePayload.hitKind;
    surface.position = (packedHitKind & PRIME_HIT_KIND_MASK) == PRIME_HIT_NONE
            ? vec3(0.0)
            : origin + direction * primePayload.t;
    surface.t = primePayload.t;
    surface.geometricNormal = primePayload.geometricNormal;
    surface.hitKind = packedHitKind & PRIME_HIT_KIND_MASK;
    surface.baseColor = primePayload.baseColor;
    surface.materialControl = primePayload.materialControl;
    surface.sectionIndex = primePayload.sectionIndex;
    surface.emitterIndex = primePayload.emitterIndex;
    surface.textureLod = primePayload.textureLod;
    surface.opacity = primePayload.opacity;
    surface.shadingNormal = primePayload.shadingNormal;
    surface.roughness = primePayload.roughness;
    surface.opticalControl = primePayload.opticalControl;
    surface.motionZFlags = packedHitKind & ~PRIME_HIT_KIND_MASK;
    surface.adjacentBaseColor = primePayload.adjacentBaseColor;
    surface.adjacentInterfaceControl = primePayload.adjacentInterfaceControl;
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

vec3 primeSurfaceOutwardShadingNormal(SurfaceInteraction surface) {
    vec3 shadingNormal = primeUnpackOctahedralNormal(surface.shadingNormal);
    return dot(shadingNormal, surface.geometricNormal) >= 0.0
            ? shadingNormal
            : -shadingNormal;
}

vec3 primeSurfaceShadingNormal(SurfaceInteraction surface, vec3 viewDirection) {
    vec3 shadingNormal = primeSurfaceOutwardShadingNormal(surface);
    return dot(surface.geometricNormal, viewDirection) >= 0.0
            ? shadingNormal
            : -shadingNormal;
}

uint primeAreaLightReceiverNormal(
        SurfaceInteraction surface, vec3 viewDirection) {
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
        vec3 viewDirection,
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
    vec3 transmittance;
    float hitDistance;
};

PrimeShadowTrace primeTraceShadow(
        vec3 physicalPosition,
        vec3 normal,
        LightSample light,
        PrimeRcVolumeStack volumeStack) {
    uint startingMediumCount = min(
            volumeStack.count, PRIME_RC_MAX_VOLUME_STACK_SIZE);
    vec3 startingExtinction0 = startingMediumCount > 0u
            ? primeShadowCanonicalExtinction(
                    volumeStack.values[0].extinction)
            : vec3(0.0);
    vec3 startingExtinction1 = startingMediumCount > 1u
            ? primeShadowCanonicalExtinction(
                    volumeStack.values[1].extinction)
            : vec3(0.0);
    primeShadowPayload.opticalDepthMomentHitDistance = vec4(0.0);
    primeShadowPayload.terminalExtinctionRayDistance = vec4(
            vec3(0.0),
            light.distance);
    primeShadowPayload.startingExtinction0Winding = vec4(
            startingExtinction0, startingMediumCount > 0u ? 1.0 : 0.0);
    primeShadowPayload.startingExtinction1Winding = vec4(
            startingExtinction1, startingMediumCount > 1u ? 1.0 : 0.0);
    primeShadowPayload.startingMediumCount = startingMediumCount;
    vec3 traceOrigin = primeOffsetRayOrigin(physicalPosition, normal, light.direction);
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
            vec3(0.0),
            vec3(1.0));
    result.hitDistance = primeNrdSanitizeHitDistance(
            primeShadowPayload.opticalDepthMomentHitDistance.w);
    primeRecordUnit(result.transmittance);
    return result;
}

PrimeDirectLightingSplit primeEvaluateVisibleDirectSplit(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 shadingNormal,
        LightSample light,
        vec3 lightRadiance,
        PrimeRcVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    PrimeDirectLightingSplit result;
    result.diffuse = vec3(0.0);
    result.specular = vec3(0.0);
    result.direction = light.direction;
    bool transmissive = primeMaterialIsTransmissive(surface.materialControl);
    bool foliage = primeMaterialIsFoliage(surface.materialControl);
    float cosine = transmissive || foliage
            ? abs(dot(shadingNormal, light.direction))
            : max(dot(shadingNormal, light.direction), 0.0);
    if (cosine <= 0.0 || light.pdf <= 0.0
            || all(lessThanEqual(lightRadiance, vec3(0.0)))) {
        return result;
    }
    if (transmissive) {
        vec3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
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
                light.pdf, evaluation.pdf);
        vec3 contribution = primeTripleProduct(
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
                light.pdf, components.pdf);
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
            light.pdf, components.pdf);
    result.diffuse = primeTripleProduct(
            lightRadiance, components.diffuseResponse, weightedInversePdf);
    result.specular = primeTripleProduct(
            lightRadiance, components.specularResponse, weightedInversePdf);
    return result;
}

bool primeDirectSampleEligible(
        SurfaceInteraction surface,
        vec3 shadingNormal,
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
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec2 sampleValue,
        PrimeRcVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    PrimePrimarySunSample result;
    result.lighting.diffuse = vec3(0.0);
    result.lighting.specular = vec3(0.0);
    result.lighting.direction = vec3(0.0);
    result.penumbra = 0.0;
    result.visibility = 0.0;
    LightSample light = primeSampleSun(integrator, surface.position, sampleValue);
    if (!primeAtmosphereDistantDirectionVisible(light.direction)) {
        return result;
    }
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, light)) {
        return result;
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            surface.position, surface.geometricNormal, light, volumeStack);
    result.penumbra = primeSigmaPackDirectionalPenumbra(shadow.hitDistance);
    result.visibility = float(shadow.hitDistance >= PRIME_NRD_FP16_MAX);
    vec3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, light) * shadow.transmittance;
    result.lighting = primeEvaluateVisibleDirectSplit(
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
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec2 treeSample,
        vec2 positionSample,
        PrimeRcVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    AreaLightSample area = primeSampleAreaLight(
            surface.position,
            primeAreaLightReceiverNormal(surface, viewDirection),
            treeSample,
            positionSample);
    if (!primeDirectSampleEligible(surface, shadingNormal, area.light)) {
        PrimeDirectLightingSplit result;
        result.diffuse = vec3(0.0);
        result.specular = vec3(0.0);
        result.direction = vec3(0.0);
        return result;
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            surface.position, surface.geometricNormal, area.light, volumeStack);
    if (shadow.hitDistance < PRIME_NRD_FP16_MAX) {
        PrimeDirectLightingSplit result;
        result.diffuse = vec3(0.0);
        result.specular = vec3(0.0);
        result.direction = vec3(0.0);
        return result;
    }
    vec3 radiance =
            primeResolveSampledAreaLightRadiance(area) * shadow.transmittance;
    return primeEvaluateVisibleDirectSplit(
            surface,
            viewDirection,
            shadingNormal,
            area.light,
            radiance,
            volumeStack,
            conditionalTransparentBranch);
}

vec3 primeEstimateDirectAreaLight(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec2 treeSample,
        vec2 positionSample,
        PrimeRcVolumeStack volumeStack) {
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    AreaLightSample area = primeSampleAreaLight(
            surface.position,
            primeAreaLightReceiverNormal(surface, viewDirection),
            treeSample,
            positionSample);
    if (!primeDirectSampleEligible(surface, shadingNormal, area.light)) {
        return vec3(0.0);
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            surface.position, surface.geometricNormal, area.light, volumeStack);
    if (shadow.hitDistance < PRIME_NRD_FP16_MAX) {
        return vec3(0.0);
    }
    vec3 radiance =
            primeResolveSampledAreaLightRadiance(area) * shadow.transmittance;
    PrimeDirectLightingSplit split = primeEvaluateVisibleDirectSplit(
            surface,
            viewDirection,
            shadingNormal,
            area.light,
            radiance,
            volumeStack,
            false);
    return split.diffuse + split.specular;
}

vec3 primeEstimateDirectSun(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec2 sampleValue,
        PrimeRcVolumeStack volumeStack) {
    LightSample light = primeSampleSun(integrator, surface.position, sampleValue);
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
    PrimeDirectLightingSplit split = primeEvaluateVisibleDirectSplit(
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
        SurfaceInteraction surface,
        vec3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeRcVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    // Stars are evaluated only when the continuation ray misses. Primary non-sun guides therefore
    // contain only the explicitly sampled area-light family.
    return primeEstimatePrimaryDirectAreaLight(
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

vec3 primeEstimateDirectLighting(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        vec3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeRcVolumeStack volumeStack) {
    vec3 result = primeEstimateDirectSun(
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
        inout PathState path,
        float sampleValue) {
    float survival = primeRussianRouletteSurvival(
            path.throughput, path.etaScale);
    primeRecordUnit(survival);
    if (sampleValue >= survival) {
        return false;
    }
    path.throughput = primeRussianRouletteReweight(path.throughput, survival);
    primeRecordNonnegative(path.throughput);
    return true;
}

void primeAccumulate(inout vec3 accumulator, vec3 contribution) {
    primeRecordRadiance(contribution);
    accumulator += contribution;
    primeRecordRadiance(accumulator);
}

void primeAccumulateAfterPrimary(
        inout PrimeIntegrationResult result,
        bool diffusePath,
        vec3 contribution) {
    if (diffusePath) {
        primeAccumulate(result.radiance.diffuse, contribution);
    } else {
        primeAccumulate(result.radiance.specular, contribution);
    }
}

// Renderer-specific integrators compose these terminal estimators. Their caller supplies whether
// the competing Area technique existed, so realtime and offline can keep independent NEE rules.
vec3 primeEvaluateEnvironmentContribution(
        PathState path, IntegratorRecord integrator) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_EMISSION, path.bounce);
    vec3 stars = primeStarmapRadiance(
            integrator, path.physicalOrigin, path.rayDirection);
    LightEvaluation sun = primeEvaluateSun(
            integrator, path.physicalOrigin, path.rayDirection);
    float sunWeight = primePreviousCannotUseMis(path)
            ? 1.0
            : primePowerHeuristic(path.previousBsdfPdf, sun.pdf);
    return path.throughput
            * (primeEnvironmentRadiance(integrator, path.rayDirection)
            + stars
            + sun.radiance * sunWeight);
}

vec3 primeEvaluateLocalHitEmission(
        PathState path,
        SurfaceInteraction surface,
        bool previousUsedAreaNee) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_EMISSION, path.bounce);
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

vec3 primeEvaluateHitEmission(
        PathState path,
        SurfaceInteraction surface,
        bool previousUsedAreaNee) {
    return path.throughput * primeEvaluateLocalHitEmission(
            path, surface, previousUsedAreaNee);
}

bool primeApplySegmentMedium(
        inout PathState path,
        SurfaceInteraction surface,
        PrimeRcVolumeStack volumeStack) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_MEDIUM, path.bounce);
    if (volumeStack.count == 0u) {
        return true;
    }
    PrimeRcVolume medium = volumeStack.values[volumeStack.count - 1u];
    path.throughput *= primeSegmentTransmittance(medium.extinction, surface.t);
    primeRecordNonnegative(path.throughput);
    return !all(equal(path.throughput, vec3(0.0)));
}

struct PrimePathScatter {
    BsdfSample bsdf;
    PrimeRcVolumeStack volumeStack;
};

PrimePathScatter primeSamplePathSurfaceWithMinimumRoughness(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 sampleValue,
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
        vec3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
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
        vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
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
        vec3 viewDirection,
        vec3 sampleValue,
        PrimeRcVolumeStack volumeStack) {
    return primeSamplePathSurfaceWithMinimumRoughness(
            surface, viewDirection, sampleValue, volumeStack, 0.0);
}

void primeSampleGuidedPathSurface(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 sampleValue,
        PrimeRcVolumeStack volumeStack,
        uint bounce,
        out PrimePathScatter scatter,
        out PrimeDenoiseAlbedos albedos) {
    scatter.volumeStack = volumeStack;
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_SURFACE, bounce);
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
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, bounce);
        scatter.bsdf = primeSampleMinecraftFoliageFromState(
                state, viewDirection, sampleValue, volumeStack);
    } else if (primeMaterialIsTransmissive(surface.materialControl)) {
        vec3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
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
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, bounce);
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
        vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
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
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, bounce);
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

const uint PRIME_SHARC_EVENT_DISCRETE = 0u;
const uint PRIME_SHARC_EVENT_DIFFUSE = 1u;
const uint PRIME_SHARC_EVENT_GLOSSY = 2u;

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
        PathState path,
        SurfaceInteraction surface,
        bool previousUsedAreaNee,
        out vec3 contribution) {
    contribution = vec3(0.0);
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
                    path, surface, previousUsedAreaNee));
    vec3 radiance;
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
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        PrimePreparedSampleBase bounceSample,
        bool discreteOnlyInterface,
        float surfaceLinearRoughness) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_PATH_ADVANCE, path.bounce);
    vec3 nextThroughput = primeProductOver(path.throughput, bsdf.response, bsdf.pdf);
    primeRecordNonnegative(nextThroughput);
    if (all(equal(nextThroughput, vec3(0.0)))) {
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
    primeRecordNonnegative(path.etaScale);
    // bounce counts every completed scatter, including discrete-only transparent interfaces.
    if (!primeRussianRouletteApplies(
            path.bounce, PRIME_RUSSIAN_ROULETTE_START)) {
        return true;
    }
    float rouletteSample = primeHashSample1D(
            bounceSample,
            PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    return primeRussianRoulette(path, rouletteSample);
}

// Queue-resident PSR state stores the bounded discrete chain as an incremental path length and
// orthogonal direction transform. The last discrete position is exactly path.physicalOrigin at every
// queue boundary, so keeping a second vec3 would increase both slot size and live registers.

void primeAppendQueuedPsrDiscrete(
        inout PrimeQueuedPsrState state,
        vec3 previousPosition,
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
        vec3 target,
        vec3 targetNormal) {
    return primeQueuedPsrAnchorDistanceValue(
            state,
            primePush.cameraPosition,
            target,
            targetNormal);
}

void primeSetQueuedPsrGuide(
        inout PrimeDenoiserGuides guides,
        SurfaceInteraction surface,
        vec3 shadingNormal,
        float linearRoughness,
        PrimeDenoiseAlbedos albedos,
        vec3 guideThroughput,
        vec3 lastPosition,
        PrimeQueuedPsrState state) {
    vec3 position = surface.position - primePush.cameraPosition;
    vec3 normal = shadingNormal;
    vec3 virtualPosition;
    vec3 virtualNormal;
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
        inout PrimeTransparentBranchResult result,
        bool diffusePath,
        vec3 contribution) {
    if (diffusePath) {
        primeAccumulate(result.diffuseRadiance, contribution);
    } else {
        primeAccumulate(result.specularRadiance, contribution);
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
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_SURFACE, path.bounce);
    primeRecordNonFinite(surface.position);
    primeRecordNonnegative(surface.t);
    primeRecordDirection(surface.geometricNormal);
    primeRecordUnit(surface.baseColor);
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
            vec3 direction =
                    primeQueuedPsrVirtualDirection(psrState, path.rayDirection);
            float lengthSquared = dot(direction, direction);
            if (primeNrdIsFinite(direction) && primeNrdIsFinite(lengthSquared)
                    && lengthSquared > 1.0e-12) {
                result.guides.primaryPosition =
                        direction * inversesqrt(lengthSquared);
                directionalGuide = true;
            }
        }
        primeAccumulateTransparentBranch(
                result,
                guideEnabled ? diffusePath : transmissionBranch,
                primeEvaluateEnvironmentContribution(path, integrator));
        return false;
    }
    if (!primeApplySegmentMedium(path, surface, volumeStack)) {
        return false;
    }

    vec3 viewDirection = -path.rayDirection;
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
    vec3 emitted = primeEvaluateHitEmission(
            path, surface, previousUsedAreaNee);
    primeAccumulateTransparentBranch(
            result, contributionDiffuse, emitted);

    if (supportsSolidAngle) {
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
        if (primarySurfaceReplacement) {
            PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
                    integrator,
                    surface,
                    viewDirection,
                    primeSobolSample2D(
                            preparedSample,
                            PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                            PRIME_SAMPLE_DIMENSION_PRIMARY),
                    volumeStack,
                    false);
            vec3 diffuseDirect =
                    path.throughput * sun.lighting.diffuse * sun.visibility;
            vec3 specularDirect =
                    path.throughput * sun.lighting.specular * sun.visibility;
            PrimeDirectLightingSplit nonsun = primeEstimatePrimaryNonsunDirect(
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
            primeAccumulate(result.diffuseRadiance, diffuseDirect);
            primeAccumulate(result.specularRadiance, specularDirect);
        } else {
            vec3 direct = path.throughput * primeEstimateDirectLighting(
                    integrator,
                    surface,
                    viewDirection,
                    preparedSample,
                    volumeStack);
            primeAccumulateTransparentBranch(
                    result,
                    guideEnabled ? diffusePath : transmissionBranch,
                    direct);
        }
    }

    vec3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimePathScatter scatter;
    PrimeDenoiseAlbedos surfaceAlbedos;
    if (primarySurfaceReplacement) {
        primeSampleGuidedPathSurface(
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
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
        scatter = primeSamplePathSurface(
                surface, viewDirection, scatterSample, volumeStack);
    }
    BsdfSample bsdf = scatter.bsdf;
    if (!primeHasScatter(bsdf)) {
        return false;
    }
    primeRecordDirection(bsdf.direction);
    primeRecordNonnegative(bsdf.response);
    primeRecordNonnegative(bsdf.pdf);
    primeRecordNonnegative(bsdf.relativeEta);
    if (primarySurfaceReplacement) {
        diffusePath = (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
        vec3 virtualDirection =
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
    result.radiance.diffuse = vec3(0.0);
    result.radiance.specular = vec3(0.0);
    result.radiance.stable = vec3(0.0);
    result.radiance.unshadowedSun = vec3(0.0);
    result.radiance.sunVisibility = 0.0;
    result.guides = primeEmptyDenoiserGuides();
    result.reflectionDiffuseRadiance = vec3(0.0);
    result.reflectionSpecularRadiance = vec3(0.0);
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
    state.diffuseAlbedoProduct = vec3(1.0);
    state.specularAlbedoProduct = vec3(0.0);
    state.diffusePath = false;
    state.primaryBounce = 0u;
    return state;
}

// Processes exactly one ordinary realtime path vertex. Primary transparent interfaces remain a
// local island because two reconstruction records share one output pixel, although only the
// checkerboard-selected record carries transport. Every other continuation can cross this
// boundary without carrying invocation-local BSDF state.
bool primeIntegrateWavefrontSurface(
        inout PathState path,
        IntegratorRecord integrator,
        inout PrimeRcVolumeStack volumeStack,
        inout PrimeDenoiserState denoiserState,
        inout PrimeIntegrationResult result,
        SurfaceInteraction surface,
        uint pathIndex) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_SURFACE, path.bounce);
    primeRecordNonFinite(surface.position);
    primeRecordNonnegative(surface.t);
    primeRecordDirection(surface.geometricNormal);
    primeRecordUnit(surface.baseColor);
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
        vec3 contribution = primeEvaluateEnvironmentContribution(path, integrator);
        if (!denoiserState.hasPrimarySurface) {
            primeAccumulate(result.radiance.stable, contribution);
        } else {
            primeAccumulateAfterPrimary(result, denoiserState.diffusePath, contribution);
        }
        return false;
    }
    if (!primeApplySegmentMedium(path, surface, volumeStack)) {
        return false;
    }


#if defined(PRIME_SHARC_QUERY)
    vec3 sharcContribution;
    if (denoiserState.hasPrimarySurface
            && primeQuerySharc(
                    path,
                    surface,
                    denoiserState.hasPrimarySurface,
                    sharcContribution)) {
        primeAccumulateAfterPrimary(
                result,
                denoiserState.diffusePath,
                sharcContribution);
        return false;
    }
#endif

    vec3 viewDirection = -path.rayDirection;
    bool previousUsedAreaNee = denoiserState.hasPrimarySurface;
    vec3 emitted = primeEvaluateHitEmission(
            path, surface, previousUsedAreaNee);
    if (!denoiserState.hasPrimarySurface) {
        primeAccumulate(result.radiance.stable, emitted);
    } else {
        primeAccumulateAfterPrimary(
                result, denoiserState.diffusePath, emitted);
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
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
        if (!denoiserState.hasPrimarySurface) {
            PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
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
                    result.radiance.unshadowedSun,
                    path.throughput * (sun.lighting.diffuse + sun.lighting.specular));
            PrimeDirectLightingSplit nonsun = primeEstimatePrimaryNonsunDirect(
                    surface,
                    viewDirection,
                    preparedSample,
                    volumeStack,
                    false);
            vec3 primaryAreaDiffuse = path.throughput * nonsun.diffuse;
            vec3 primaryAreaSpecular = path.throughput * nonsun.specular;
            primeAccumulate(result.radiance.diffuse, primaryAreaDiffuse);
            primeAccumulate(result.radiance.specular, primaryAreaSpecular);
            result.guides.primaryAreaDiffuse = primaryAreaDiffuse;
            result.guides.primaryAreaSpecular = primaryAreaSpecular;
            if (any(greaterThan(primaryAreaDiffuse, vec3(0.0)))
                    || any(greaterThan(primaryAreaSpecular, vec3(0.0)))) {
                result.guides.primaryAreaDirection = nonsun.direction;
            }
        } else {
            vec3 direct = path.throughput * primeEstimateDirectLighting(
                    integrator,
                    surface,
                    viewDirection,
                    preparedSample,
                    volumeStack);
            primeAccumulateAfterPrimary(
                    result,
                    denoiserState.diffusePath,
                    direct);
        }
    }

    vec3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimePathScatter scatter;
    PrimeDenoiseAlbedos surfaceAlbedos;
    if (!denoiserState.reachedSolidAngle) {
        primeSampleGuidedPathSurface(
                surface,
                viewDirection,
                scatterSample,
                volumeStack,
                path.bounce,
                scatter,
                surfaceAlbedos);
    } else {
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
        scatter = primeSamplePathSurface(
                surface, viewDirection, scatterSample, volumeStack);
    }
    BsdfSample bsdf = scatter.bsdf;
    volumeStack = scatter.volumeStack;
    bool hasScatter = primeHasScatter(bsdf);
    if (hasScatter) {
        primeRecordDirection(bsdf.direction);
        primeRecordNonnegative(bsdf.response);
        primeRecordNonnegative(bsdf.pdf);
        primeRecordNonnegative(bsdf.relativeEta);
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
