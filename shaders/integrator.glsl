#ifndef PRIME_INTEGRATOR_GLSL
#define PRIME_INTEGRATOR_GLSL

// The entire estimator operates in linear Rec.2020 D65. Scheduling changes may move this state
// between kernels, but they must not reinterpret it as encoded sRGB or another RGB basis.

const uint PRIME_HIT_NONE = 0u;
const uint PRIME_HIT_SURFACE = 1u;
const uint PRIME_PATH_PREVIOUS_DELTA = 1u;
// This path vertex deliberately had no next-event estimate. Its first emitter/environment hit
// therefore has no competing light-sampling technique and must receive MIS weight one.
const uint PRIME_PATH_PREVIOUS_NO_AREA_NEE = 2u;

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

struct PrimeContinuationResult {
    vec3 radiance;
    float firstHitDistance;
};

struct PrimeReferenceResult {
    vec3 radiance;
    float primaryDistance;
};

// Bookkeeping that maps a complete physical path into the signal/guide contract. Keeping it in a
// named state object makes the estimator loop independent of any particular NRD or NGX resource.
struct PrimeDenoiserState {
    bool hasPrimarySurface;
    bool reachedNonDelta;
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

SurfaceInteraction primeTraceSurface(vec3 origin, vec3 direction) {
    primePayload.position = vec3(0.0);
    primePayload.t = 0.0;
    primePayload.geometricNormal = vec3(0.0, 1.0, 0.0);
    primePayload.hitKind = PRIME_HIT_NONE;
    primePayload.baseColor = vec3(0.0);
    primePayload.traceKind = 0u;
    primePayload.sectionIndex = 0u;
    primePayload.emitterIndex = PRIME_NO_LIGHT_INDEX;
    primePayload.textureLod = 0u;
    primePayload.opacity = 0u;
    primePayload.shadingNormal = primePackOctahedralNormal(vec3(0.0, 1.0, 0.0));
    primePayload.labPbrNormal = packUnorm4x8(vec4(0.5, 0.5, 1.0, 1.0));
    primePayload.labPbrSpecular = packUnorm4x8(vec4(0.0, 4.0 / 255.0, 0.0, 1.0));
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
    uint coherenceHint = hitObjectIsHitEXT(hitObject)
            ? uint(hitObjectGetInstanceCustomIndexEXT(hitObject))
            : 0u;
    reorderThreadEXT(hitObject, coherenceHint, 8u);
    hitObjectExecuteShaderEXT(hitObject, 0);
#else
    traceRayEXT(primeScene, gl_RayFlagsNoneEXT, 0xff, 0, 1, 0,
            origin, 0.0, direction, 1000000.0, 0);
#endif
    SurfaceInteraction surface;
    surface.position = primePayload.position;
    surface.t = primePayload.t;
    surface.geometricNormal = primePayload.geometricNormal;
    surface.hitKind = primePayload.hitKind;
    surface.baseColor = primePayload.baseColor;
    surface.materialFlags = primePayload.traceKind;
    surface.sectionIndex = primePayload.sectionIndex;
    surface.emitterIndex = primePayload.emitterIndex;
    surface.textureLod = primePayload.textureLod;
    surface.opacity = primePayload.opacity;
    surface.shadingNormal = primePayload.shadingNormal;
    surface.labPbrNormal = primePayload.labPbrNormal;
    surface.labPbrSpecular = primePayload.labPbrSpecular;
    return surface;
}

bool primeKnownHitKind(SurfaceInteraction surface) {
    return surface.hitKind == PRIME_HIT_NONE || surface.hitKind == PRIME_HIT_SURFACE;
}

bool primePreviousCannotUseSunMis(PathState path) {
    return path.bounce == 0u
            || (path.flags & PRIME_PATH_PREVIOUS_DELTA) != 0u;
}

bool primePreviousCannotUseAreaMis(PathState path) {
    return path.bounce == 0u
            || (path.flags & (
                    PRIME_PATH_PREVIOUS_DELTA
                            | PRIME_PATH_PREVIOUS_NO_AREA_NEE)) != 0u;
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

float primeSurfaceOpacity(SurfaceInteraction surface) {
    return clamp(uintBitsToFloat(surface.opacity), 0.0, 1.0);
}

float primeSurfaceLinearRoughness(SurfaceInteraction surface) {
    if (primeHasLabPbrSpecular(surface.materialFlags)) {
        // RoboCute squares this value to obtain the microfacet alpha. NRD's LINEAR encoding
        // expects the same pre-squared roughness, not alpha itself.
        return primeDecodeAndTranslateLabPbr(
                surface.labPbrNormal,
                surface.labPbrSpecular,
                surface.materialFlags).perceptualRoughness;
    }
    return primeMaterialLinearRoughness(surface.materialFlags);
}

float primeTraceShadowHitDistance(vec3 physicalPosition, vec3 normal, LightSample light) {
    primeShadowHitDistanceBits = floatBitsToUint(0.0);
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
    return primeNrdSanitizeHitDistance(uintBitsToFloat(primeShadowHitDistanceBits));
}

bool primeVisible(vec3 physicalPosition, vec3 normal, LightSample light) {
    return primeTraceShadowHitDistance(physicalPosition, normal, light) >= PRIME_NRD_FP16_MAX;
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
    bool transmissive = primeMaterialIsTransmissive(surface.materialFlags);
    bool foliage = primeMaterialIsFoliage(surface.materialFlags);
    float cosine = transmissive || foliage
            ? abs(dot(shadingNormal, light.direction))
            : max(dot(shadingNormal, light.direction), 0.0);
    if (cosine <= 0.0 || light.pdf <= 0.0
            || all(lessThanEqual(lightRadiance, vec3(0.0)))) {
        return result;
    }
    if (transmissive) {
        // Segment attenuation is owned by the path's active medium before this vertex. Direct
        // evaluation describes only the current interface, hence rayT=0 here.
        vec3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
        BsdfEvaluation evaluation = conditionalTransparentBranch
                ? primeEvaluateMinecraftTransmissionBranch(
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        outwardNormal,
                        surface.materialFlags,
                        surface.labPbrNormal,
                        surface.labPbrSpecular,
                        viewDirection,
                        light.direction,
                        0.0,
                        volumeStack)
                : primeEvaluateMinecraftTransmission(
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        outwardNormal,
                        surface.materialFlags,
                        surface.labPbrNormal,
                        surface.labPbrSpecular,
                        viewDirection,
                        light.direction,
                        0.0,
                        volumeStack);
        float weightedInversePdf = primePowerHeuristicOverPdf(
                light.pdf, evaluation.pdf);
        vec3 contribution = primeTripleProduct(
                lightRadiance, evaluation.response, weightedInversePdf);
        bool reflection = dot(outwardNormal, viewDirection)
                * dot(outwardNormal, light.direction) >= 0.0;
        // At a transparent primary surface the two NRD lanes are transmission and reflection.
        // The same split also lets RR derive Color Before Transparency from the actual
        // transmission estimate without another guide ray.
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
                surface.labPbrNormal,
                surface.labPbrSpecular,
                surface.materialFlags,
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
            surface.labPbrNormal,
            surface.labPbrSpecular,
            surface.materialFlags,
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
            && (primeMaterialIsTransmissive(surface.materialFlags)
                    || primeMaterialIsFoliage(surface.materialFlags)
                    || dot(shadingNormal, light.direction) > 0.0);
}

bool primeDirectSampleVisible(
        SurfaceInteraction surface,
        vec3 shadingNormal,
        LightSample light) {
    return primeDirectSampleEligible(surface, shadingNormal, light)
            && primeVisible(surface.position, surface.geometricNormal, light);
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
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, light)) {
        return result;
    }
    float shadowHitDistance = primeTraceShadowHitDistance(
            surface.position, surface.geometricNormal, light);
    result.penumbra = primeSigmaPackDirectionalPenumbra(shadowHitDistance);
    result.visibility = float(shadowHitDistance >= PRIME_NRD_FP16_MAX);
    vec3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, light);
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
        vec3 treeSample,
        vec2 positionSample,
        PrimeRcVolumeStack volumeStack,
        bool conditionalTransparentBranch) {
    AreaLightSample area = primeSampleAreaLight(
            surface.position, treeSample, positionSample);
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleVisible(surface, shadingNormal, area.light)) {
        PrimeDirectLightingSplit result;
        result.diffuse = vec3(0.0);
        result.specular = vec3(0.0);
        result.direction = vec3(0.0);
        return result;
    }
    vec3 radiance = primeResolveSampledAreaLightRadiance(area);
    return primeEvaluateVisibleDirectSplit(
            surface,
            viewDirection,
            shadingNormal,
            area.light,
            radiance,
            volumeStack,
            conditionalTransparentBranch);
}

vec3 primeEstimateDirectSun(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec2 sampleValue,
        PrimeRcVolumeStack volumeStack) {
    LightSample light = primeSampleSun(integrator, surface.position, sampleValue);
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleVisible(surface, shadingNormal, light)) {
        return vec3(0.0);
    }
    vec3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, light);
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

vec3 primeEstimateDirectAreaLight(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 treeSample,
        vec2 positionSample,
        PrimeRcVolumeStack volumeStack) {
    AreaLightSample area = primeSampleAreaLight(
            surface.position, treeSample, positionSample);
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleVisible(surface, shadingNormal, area.light)) {
        return vec3(0.0);
    }
    vec3 radiance = primeResolveSampledAreaLightRadiance(area);
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
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
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
    result += primeEstimateDirectAreaLight(
            surface,
            viewDirection,
            primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_SECONDARY),
            volumeStack);
    return result;
}

bool primeRussianRoulette(
        inout PathState path,
        float sampleValue) {
    float survival = primeRussianRouletteSurvival(path.throughput);
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

// Realtime and screenshot rendering share this one complete path estimator. Keeping segment
// attenuation, emitter MIS, BSDF dispatch and path advancement here prevents their physical rules
// from drifting between the two modes.
vec3 primeEvaluateEnvironmentContribution(
        PathState path, IntegratorRecord integrator) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_EMISSION, path.bounce);
    vec3 stars = primeStarmapRadiance(
            integrator, path.physicalOrigin, path.rayDirection);
    LightEvaluation sun = primeEvaluateSun(
            integrator, path.physicalOrigin, path.rayDirection);
    float sunWeight = primePreviousCannotUseSunMis(path)
            ? 1.0
            : primePowerHeuristic(path.previousBsdfPdf, sun.pdf);
    return path.throughput
            * (primeEnvironmentRadiance(integrator, path.rayDirection)
            + stars
            + sun.radiance * sunWeight);
}

vec3 primeEvaluateHitEmission(
        PathState path, SurfaceInteraction surface) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_EMISSION, path.bounce);
    LightEvaluation hitAreaLight = primeEvaluateAreaLight(
            surface, path.physicalOrigin, path.rayDirection);
    float hitAreaWeight = primePreviousCannotUseAreaMis(path)
            ? 1.0
            : primePowerHeuristic(path.previousBsdfPdf, hitAreaLight.pdf);
    return primeTripleProduct(path.throughput, hitAreaLight.radiance, hitAreaWeight);
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

bool primeIsPureDeltaInterface(SurfaceInteraction surface) {
    if (!primeMaterialIsTransmissive(surface.materialFlags)) {
        return false;
    }
    return primeSurfaceLinearRoughness(surface) == 0.0;
}

struct PrimePathScatter {
    BsdfSample bsdf;
    PrimeRcVolumeStack volumeStack;
};

PrimePathScatter primeSamplePathSurface(
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
        vec3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
        PrimeRcState state = primeMinecraftTransmissionState(
                surface.baseColor,
                primeSurfaceOpacity(surface),
                outwardNormal,
                surface.materialFlags,
                surface.labPbrNormal,
                surface.labPbrSpecular,
                viewDirection,
                surface.t,
                volumeStack);
        PrimeTransmissiveBsdfSample transmitted =
                primeSampleMinecraftTransmissionFromState(
                        state,
                        outwardNormal,
                        viewDirection,
                        sampleValue,
                        volumeStack);
        result.bsdf = transmitted.bsdfSample;
        result.volumeStack = transmitted.volumeStack;
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
                state,
                shadingNormal,
                viewDirection,
                sampleValue,
                volumeStack);
    }
    return result;
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
        albedos = primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_FOLIAGE);
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, bounce);
        scatter.bsdf = primeSampleMinecraftFoliageFromState(
                state, viewDirection, sampleValue, volumeStack);
    } else if (primeMaterialIsTransmissive(surface.materialFlags)) {
        vec3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
        PrimeRcState state = primeMinecraftTransmissionState(
                surface.baseColor,
                primeSurfaceOpacity(surface),
                outwardNormal,
                surface.materialFlags,
                surface.labPbrNormal,
                surface.labPbrSpecular,
                viewDirection,
                surface.t,
                volumeStack);
        albedos = primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_TRANSMISSIVE);
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, bounce);
        PrimeTransmissiveBsdfSample transmitted =
                primeSampleMinecraftTransmissionFromState(
                        state,
                        outwardNormal,
                        viewDirection,
                        sampleValue,
                        volumeStack);
        scatter.bsdf = transmitted.bsdfSample;
        scatter.volumeStack = transmitted.volumeStack;
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

bool primeIsDeltaSample(BsdfSample bsdf) {
    return (bsdf.eventFlags & PRIME_BSDF_EVENT_DELTA) != 0u;
}

bool primeIsNonDeltaSample(BsdfSample bsdf) {
    uint nonDeltaFlags = PRIME_BSDF_EVENT_DIFFUSE | PRIME_BSDF_EVENT_GLOSSY;
    return (bsdf.eventFlags & nonDeltaFlags) != 0u && !primeIsDeltaSample(bsdf);
}

bool primeAdvancePath(
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        PrimePreparedSampleBase bounceSample,
        bool pureDeltaInterface) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_PATH_ADVANCE, path.bounce);
    vec3 nextThroughput = primeProductOver(path.throughput, bsdf.response, bsdf.pdf);
    primeRecordNonnegative(nextThroughput);
    if (all(equal(nextThroughput, vec3(0.0)))) {
        return false;
    }
    path.throughput = nextThroughput;
    path.physicalOrigin = surface.position;
    path.traceOrigin = primeOffsetRayOrigin(
            path.physicalOrigin, surface.geometricNormal, bsdf.direction);
    path.rayDirection = bsdf.direction;
    path.previousBsdfPdf = bsdf.pdf;
    path.flags = (bsdf.eventFlags & PRIME_BSDF_EVENT_DELTA) != 0u
            ? PRIME_PATH_PREVIOUS_DELTA
            : 0u;
    // A pure delta transparent interface has no NEE vertex. Excluding it from RR avoids turning
    // long glass chains into mostly-zero samples without adding expensive light-tree work.
    // Cutout/null coverage is rejected by any-hit and never reaches this state transition.
    if (pureDeltaInterface) {
        return true;
    }
    uint rrDepth = path.rrDepth++;
    if (rrDepth < PRIME_RUSSIAN_ROULETTE_START) {
        return true;
    }
    float rouletteSample = primeHashSample1D(
            bounceSample,
            PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    return primeRussianRoulette(path, rouletteSample);
}

// PSR state stays invocation-local. Persisting every interface would add full-frame bandwidth;
// overflow merely falls back to the real first interface and never truncates transport.
const uint PRIME_PSR_DELTA_CAPACITY = 8u;

struct PrimePsrDeltaChain {
    vec4 positionEta[PRIME_PSR_DELTA_CAPACITY];
    vec4 normalEvent[PRIME_PSR_DELTA_CAPACITY];
    uint count;
    bool overflowed;
};

PrimePsrDeltaChain primeEmptyPsrDeltaChain() {
    PrimePsrDeltaChain chain;
    chain.count = 0u;
    chain.overflowed = false;
    return chain;
}

void primeAppendPsrDelta(
        inout PrimePsrDeltaChain chain,
        SurfaceInteraction surface,
        BsdfSample bsdf) {
    if (chain.count == PRIME_PSR_DELTA_CAPACITY) {
        chain.overflowed = true;
        return;
    }
    bool transmission = (bsdf.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u;
    chain.positionEta[chain.count] = vec4(
            surface.position,
            transmission ? bsdf.relativeEta : 1.0);
    chain.normalEvent[chain.count] = vec4(
            surface.geometricNormal,
            uintBitsToFloat(bsdf.eventFlags));
    chain.count++;
}

vec3 primePsrVirtualDirection(PrimePsrDeltaChain chain, vec3 direction) {
    vec3 transformed = direction;
    for (int index = int(chain.count) - 1; index >= 0; --index) {
        uint eventFlags = floatBitsToUint(chain.normalEvent[index].w);
        if ((eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u) {
            vec3 normal = chain.normalEvent[index].xyz;
            transformed -= 2.0 * normal * dot(transformed, normal);
        }
    }
    return transformed;
}

bool primeBuildPsrGuide(
        PrimePsrDeltaChain chain,
        vec3 target,
        vec3 targetNormal,
        out vec3 virtualPosition,
        out vec3 virtualNormal) {
    if (chain.count == 0u || chain.overflowed) {
        return false;
    }
    vec3 firstSegment = chain.positionEta[0].xyz - primePush.cameraPosition;
    float pathLength = length(firstSegment);
    for (uint index = 1u; index < chain.count; ++index) {
        pathLength += length(
                chain.positionEta[index].xyz - chain.positionEta[index - 1u].xyz);
    }
    pathLength += length(target - chain.positionEta[chain.count - 1u].xyz);
    if (!(length(firstSegment) > 0.0) || !(pathLength > 0.0)) {
        return false;
    }
    virtualPosition = normalize(firstSegment) * pathLength;
    virtualNormal = primePsrVirtualDirection(chain, targetNormal);
    return true;
}

float primePlanarAnchorDistance(
        PrimePsrDeltaChain chain,
        vec3 target,
        vec3 targetNormal) {
    if (chain.overflowed) {
        return -1.0;
    }
    vec3 firstPoint = chain.count == 0u ? target : chain.positionEta[0].xyz;
    vec3 firstSegment = firstPoint - primePush.cameraPosition;
    float firstLengthSquared = dot(firstSegment, firstSegment);
    if (!primeNrdIsFinite(firstLengthSquared) || !(firstLengthSquared > 1.0e-12)) {
        return -1.0;
    }
    vec3 primaryDirection = firstSegment * inversesqrt(firstLengthSquared);
    float denominator = dot(primaryDirection, targetNormal);
    if (!primeNrdIsFinite(denominator) || !(abs(denominator) > 1.0e-4)) {
        return -1.0;
    }
    float distance = dot(target - primePush.cameraPosition, targetNormal) / denominator;
    if (!primeNrdIsFinite(distance) || !(distance > 0.0)) {
        return -1.0;
    }
    return distance;
}

void primeSetPsrGuide(
        inout PrimeDenoiserGuides guides,
        SurfaceInteraction surface,
        vec3 shadingNormal,
        float linearRoughness,
        PrimeDenoiseAlbedos albedos,
        vec3 guideThroughput,
        PrimePsrDeltaChain chain) {
    vec3 position = surface.position - primePush.cameraPosition;
    vec3 normal = shadingNormal;
    vec3 virtualPosition;
    vec3 virtualNormal;
    if (primeBuildPsrGuide(
            chain, surface.position, normal, virtualPosition, virtualNormal)) {
        position = virtualPosition;
        normal = virtualNormal;
    }
    guides.primaryDistance = length(position);
    guides.primaryPosition = position;
    guides.primaryAlbedo = guideThroughput * albedos.diffuse;
    guides.primaryNormal = normal;
    guides.primaryHitKind = surface.hitKind;
    guides.primaryMaterialFlags = surface.materialFlags;
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

vec3 primeEvaluateHitEmissionWithoutAreaNee(
        PathState path, SurfaceInteraction surface) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_EMISSION, path.bounce);
    return path.throughput * primeEvaluateAreaEmission(surface, path.rayDirection);
}

// Plain transport for consumers that need radiance but no denoiser surface replacement. It keeps
// both fixed first-interface lobes physical while preventing DLSS reflection and native noisy
// output from retaining NRD's guide state across every bounce.
PrimeContinuationResult primeIntegrateContinuation(
        PathState path,
        IntegratorRecord integrator,
        PrimeRcVolumeStack volumeStack) {
    PrimeContinuationResult result;
    result.radiance = vec3(0.0);
    result.firstHitDistance = 0.0;
    bool firstSegment = true;
    uint maximumBounces = min(primePush.path.z & 0xffffu, 256u);
    [[dont_unroll]]
    for (; path.bounce < maximumBounces; ++path.bounce) {
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_TRACE, path.bounce);
        SurfaceInteraction surface = primeTraceSurface(path.traceOrigin, path.rayDirection);
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_SURFACE, path.bounce);
        primeRecordNonFinite(surface.position);
        primeRecordNonnegative(surface.t);
        primeRecordDirection(surface.geometricNormal);
        primeRecordUnit(surface.baseColor);
        if (!primeKnownHitKind(surface)) {
            break;
        }
        if (firstSegment) {
            result.firstHitDistance = surface.hitKind == PRIME_HIT_NONE
                    ? PRIME_NRD_FP16_MAX
                    : primeNrdSanitizeHitDistance(surface.t);
            firstSegment = false;
        }
        if (surface.hitKind == PRIME_HIT_NONE) {
            primeAccumulate(
                    result.radiance,
                    primeEvaluateEnvironmentContribution(path, integrator));
            break;
        }
        if (!primeApplySegmentMedium(path, surface, volumeStack)) {
            break;
        }

        vec3 viewDirection = -path.rayDirection;
        primeAccumulate(result.radiance, primeEvaluateHitEmission(path, surface));
        PrimePreparedSampleBase preparedSample =
                primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
        bool pureDeltaInterface = primeIsPureDeltaInterface(surface);
        if (!pureDeltaInterface) {
            primeSetNumericalContext(PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
            primeAccumulate(
                    result.radiance,
                    path.throughput * primeEstimateDirectLighting(
                            integrator,
                            surface,
                            viewDirection,
                            preparedSample,
                            volumeStack));
        }

        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
        PrimePathScatter scatter = primeSamplePathSurface(
                surface,
                viewDirection,
                primeSobolSample3D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                        PRIME_SAMPLE_DIMENSION_PRIMARY),
                volumeStack);
        BsdfSample bsdf = scatter.bsdf;
        if (!primeHasScatter(bsdf)) {
            break;
        }
        primeRecordDirection(bsdf.direction);
        primeRecordNonnegative(bsdf.response);
        primeRecordNonnegative(bsdf.pdf);
        primeRecordNonnegative(bsdf.relativeEta);
        volumeStack = scatter.volumeStack;
        if (!primeAdvancePath(
                path, surface, bsdf, preparedSample, pureDeltaInterface)) {
            break;
        }
    }
    return result;
}

uint primeTransparentGuideMode() {
    return (primePush.path.z >> PRIME_PATH_TRANSPARENT_GUIDE_MODE_SHIFT)
            & PRIME_PATH_TRANSPARENT_GUIDE_MODE_MASK;
}

// Continues one fixed first-interface branch. PSR capture is folded into the existing traversal:
// the first finite surface supplies both material data and the already-required lighting/BSDF
// work, so adding the second REBLUR history performs no guide ray or duplicate material lookup.
PrimeTransparentBranchResult primeIntegrateTransparentBranch(
        PathState path,
        IntegratorRecord integrator,
        PrimeRcVolumeStack volumeStack,
        SurfaceInteraction firstInterface,
        vec3 firstViewDirection,
        vec3 firstShadingNormal,
        float firstLinearRoughness,
        PrimeDenoiseAlbedos firstAlbedos,
        vec3 firstGuideThroughput,
        BsdfSample firstBsdf,
        bool transmissionBranch) {
    PrimeTransparentBranchResult result;
    result.diffuseRadiance = vec3(0.0);
    result.specularRadiance = vec3(0.0);
    result.guides = primeEmptyDenoiserGuides();
    result.firstHitDistance = 0.0;
    result.anchorDistance = -1.0;
    result.directionalGuide = false;
    PrimePsrDeltaChain deltaChain = primeEmptyPsrDeltaChain();
    bool hasGuide = (firstBsdf.eventFlags & PRIME_BSDF_EVENT_DELTA) == 0u;
    bool diffusePath = transmissionBranch;
    uint guideBounce = path.bounce - 1u;
    if (hasGuide) {
        primeSetPsrGuide(
                result.guides,
                firstInterface,
                firstShadingNormal,
                firstLinearRoughness,
                firstAlbedos,
                firstGuideThroughput,
                deltaChain);
        if (transmissionBranch) {
            result.anchorDistance = primePlanarAnchorDistance(
                    deltaChain,
                    firstInterface.position,
                    firstInterface.geometricNormal);
        }
        if (diffusePath) {
            result.guides.diffuseDirection = firstBsdf.direction;
        } else {
            result.guides.specularDirection = firstBsdf.direction;
        }
    } else {
        primeAppendPsrDelta(deltaChain, firstInterface, firstBsdf);
    }

    uint maximumBounces = min(primePush.path.z & 0xffffu, 256u);
    bool firstSegment = true;
    [[dont_unroll]]
    for (; path.bounce < maximumBounces; ++path.bounce) {
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_TRACE, path.bounce);
        SurfaceInteraction surface = primeTraceSurface(path.traceOrigin, path.rayDirection);
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_SURFACE, path.bounce);
        primeRecordNonFinite(surface.position);
        primeRecordNonnegative(surface.t);
        primeRecordDirection(surface.geometricNormal);
        primeRecordUnit(surface.baseColor);
        if (!primeKnownHitKind(surface)) {
            break;
        }
        if (firstSegment) {
            result.firstHitDistance = surface.hitKind == PRIME_HIT_NONE
                    ? PRIME_NRD_FP16_MAX
                    : primeNrdSanitizeHitDistance(surface.t);
            firstSegment = false;
        }
        if (hasGuide && path.bounce == guideBounce + 1u) {
            float hitDistance = surface.hitKind == PRIME_HIT_NONE
                    ? PRIME_NRD_FP16_MAX
                    : primeNrdSanitizeHitDistance(surface.t);
            if (diffusePath) {
                result.guides.diffuseHitDistance = hitDistance;
            } else {
                result.guides.specularHitDistance = hitDistance;
            }
        }
        if (surface.hitKind == PRIME_HIT_NONE) {
            if (!transmissionBranch && !hasGuide
                    && deltaChain.count > 0u && !deltaChain.overflowed) {
                vec3 direction = primePsrVirtualDirection(deltaChain, path.rayDirection);
                float lengthSquared = dot(direction, direction);
                if (primeNrdIsFinite(direction) && primeNrdIsFinite(lengthSquared)
                        && lengthSquared > 1.0e-12) {
                    // The same environment radiance reappears along the inverse-reflected world
                    // direction. Keep it as a direction so camera translation cannot drag an
                    // infinite sun/sky reflection across the finite water interface.
                    result.guides.primaryPosition = direction * inversesqrt(lengthSquared);
                    result.directionalGuide = true;
                }
            }
            primeAccumulateTransparentBranch(
                    result,
                    diffusePath,
                    primeEvaluateEnvironmentContribution(path, integrator));
            break;
        }
        if (!primeApplySegmentMedium(path, surface, volumeStack)) {
            break;
        }

        vec3 viewDirection = -path.rayDirection;
        PrimePreparedSampleBase preparedSample =
                primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
        bool transmissive = primeMaterialIsTransmissive(surface.materialFlags);
        float surfaceLinearRoughness = (transmissive || !hasGuide)
                ? primeSurfaceLinearRoughness(surface)
                : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
        bool pureDeltaInterface = transmissive && surfaceLinearRoughness == 0.0;
        bool primarySurfaceReplacement = !hasGuide && !pureDeltaInterface;
        PrimeDenoiseAlbedos surfaceAlbedos;

        primeAccumulateTransparentBranch(
                result,
                primarySurfaceReplacement ? true : diffusePath,
                primeEvaluateHitEmission(path, surface));
        if (!pureDeltaInterface) {
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
                PrimeDirectLightingSplit nonsun = primeEstimatePrimaryNonsunDirect(
                        surface,
                        viewDirection,
                        preparedSample,
                        volumeStack,
                        false);
                vec3 diffuseDirect = path.throughput
                        * (sun.lighting.diffuse * sun.visibility
                                + nonsun.diffuse);
                vec3 specularDirect = path.throughput
                        * (sun.lighting.specular * sun.visibility
                                + nonsun.specular);
                primeAccumulate(result.diffuseRadiance, diffuseDirect);
                primeAccumulate(result.specularRadiance, specularDirect);
                result.guides.primaryAreaDiffuse =
                        path.throughput * nonsun.diffuse;
                result.guides.primaryAreaSpecular =
                        path.throughput * nonsun.specular;
                result.guides.primaryAreaDirection = nonsun.direction;
            } else {
                vec3 direct = path.throughput * primeEstimateDirectLighting(
                        integrator,
                        surface,
                        viewDirection,
                        preparedSample,
                        volumeStack);
                primeAccumulateTransparentBranch(result, diffusePath, direct);
            }
        }

        vec3 scatterSample = primeSobolSample3D(
                preparedSample,
                PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                PRIME_SAMPLE_DIMENSION_PRIMARY);
        PrimePathScatter scatter;
        if (primarySurfaceReplacement) {
            primeSampleGuidedPathSurface(
                    surface,
                    viewDirection,
                    scatterSample,
                    volumeStack,
                    path.bounce,
                    scatter,
                    surfaceAlbedos);
            vec3 surfaceShadingNormal =
                    primeSurfaceShadingNormal(surface, viewDirection);
            primeSetPsrGuide(
                    result.guides,
                    surface,
                    surfaceShadingNormal,
                    surfaceLinearRoughness,
                    surfaceAlbedos,
                    path.throughput,
                    deltaChain);
            if (transmissionBranch) {
                result.anchorDistance = primePlanarAnchorDistance(
                        deltaChain,
                        surface.position,
                        surface.geometricNormal);
            }
            hasGuide = true;
            guideBounce = path.bounce;
        } else {
            primeSetNumericalContext(PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
            scatter = primeSamplePathSurface(
                    surface, viewDirection, scatterSample, volumeStack);
        }
        BsdfSample bsdf = scatter.bsdf;
        bool hasScatter = primeHasScatter(bsdf);
        if (hasScatter) {
            primeRecordDirection(bsdf.direction);
            primeRecordNonnegative(bsdf.response);
            primeRecordNonnegative(bsdf.pdf);
            primeRecordNonnegative(bsdf.relativeEta);
        }
        if (!hasScatter) {
            break;
        }
        if (primarySurfaceReplacement) {
            diffusePath = (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
            vec3 virtualDirection = primePsrVirtualDirection(deltaChain, bsdf.direction);
            if (diffusePath) {
                result.guides.diffuseDirection = virtualDirection;
            } else {
                result.guides.specularDirection = virtualDirection;
            }
        } else if (!hasGuide && pureDeltaInterface
                && (bsdf.eventFlags & PRIME_BSDF_EVENT_DELTA) != 0u) {
            primeAppendPsrDelta(deltaChain, surface, bsdf);
        }
        volumeStack = scatter.volumeStack;
        if (!primeAdvancePath(
                path, surface, bsdf, preparedSample, pureDeltaInterface)) {
            break;
        }
    }
    if (!hasGuide) {
        // A delta chain that terminates at the environment has no finite PSR. The real first
        // interface is a stable, bounded fallback and keeps this sample denoisable without
        // inventing a virtual material or tracing another ray.
        vec3 directionalPosition = result.guides.primaryPosition;
        primeSetPsrGuide(
                result.guides,
                firstInterface,
                firstShadingNormal,
                firstLinearRoughness,
                firstAlbedos,
                firstGuideThroughput,
                primeEmptyPsrDeltaChain());
        if (result.directionalGuide) {
            result.guides.primaryPosition = directionalPosition;
        }
        if (transmissionBranch) {
            result.guides.diffuseDirection = firstBsdf.direction;
            result.guides.diffuseHitDistance = PRIME_NRD_FP16_MAX;
        } else {
            result.guides.specularDirection = firstBsdf.direction;
            result.guides.specularHitDistance = PRIME_NRD_FP16_MAX;
        }
    }
    return result;
}

PrimeIntegrationResult primeIntegrateWithVolume(
        PathState path,
        IntegratorRecord integrator,
        PrimeRcVolumeStack volumeStack) {
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
    PrimeDenoiserState denoiserState;
    denoiserState.hasPrimarySurface = false;
    denoiserState.reachedNonDelta = false;
    denoiserState.diffuseAlbedoProduct = vec3(1.0);
    denoiserState.specularAlbedoProduct = vec3(0.0);
    denoiserState.diffusePath = false;
    denoiserState.primaryBounce = 0u;
    // This stack is path state, not temporary BSDF state. It must survive every surface bounce so
    // nested air/water/glass transitions use the IOR below the current medium and so absorption is
    // applied exactly once to the segment that was actually travelled.
    // path.z stays inside Vulkan's guaranteed 128-byte push range: low 16 bits are the bounce cap,
    // bits 16..28 are the exact one-based reconstruction jitter phase (zero selects screenshot
    // accumulation), bits 29..30 select the transparent-guide consumer, and bit 31 says that the
    // camera lies inside the water volume.
    uint maximumBounces = min(primePush.path.z & 0xffffu, 256u);
    // The cap is dynamic and intentionally large for pathological transparent stacks. Preserve
    // the loop so the native compiler cannot turn the full integrator into hundreds of copies
    // during cold pipeline creation.
    [[dont_unroll]]
    for (path.bounce = 0u; path.bounce < maximumBounces; ++path.bounce) {
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_TRACE, path.bounce);
        SurfaceInteraction surface = primeTraceSurface(path.traceOrigin, path.rayDirection);
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_SURFACE, path.bounce);
        primeRecordNonFinite(surface.position);
        primeRecordNonnegative(surface.t);
        primeRecordDirection(surface.geometricNormal);
        primeRecordUnit(surface.baseColor);
        if (!primeKnownHitKind(surface)) {
            break;
        }
        vec3 viewDirection = -path.rayDirection;
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
            break;
        }

        if (!primeApplySegmentMedium(path, surface, volumeStack)) {
            break;
        }

        bool transmissive = primeMaterialIsTransmissive(surface.materialFlags);
        bool firstTransparent = !denoiserState.hasPrimarySurface && transmissive;
        vec3 emitted = primeEvaluateHitEmission(path, surface);
        if (firstTransparent) {
            // Interface-local emission is foreground energy and must not enter RR's transmitted
            // Color Before Transparency signal.
            primeAccumulate(result.reflectionSpecularRadiance, emitted);
        } else if (!denoiserState.hasPrimarySurface) {
            primeAccumulate(result.radiance.stable, emitted);
        } else {
            primeAccumulateAfterPrimary(result, denoiserState.diffusePath, emitted);
        }

        PrimePreparedSampleBase preparedSample =
                primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
        float surfaceLinearRoughness =
                (transmissive || !denoiserState.reachedNonDelta)
                ? primeSurfaceLinearRoughness(surface)
                : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
        bool pureDeltaInterface = transmissive && surfaceLinearRoughness == 0.0;
        if (!pureDeltaInterface) {
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
                        firstTransparent);
                PrimeDirectLightingSplit nonsun = primeEstimatePrimaryNonsunDirect(
                        surface,
                        viewDirection,
                        preparedSample,
                        volumeStack,
                        firstTransparent);
                if (firstTransparent) {
                    // SIGMA has one shadow signal and cannot preserve the reflection/transmission
                    // partition. Keep the visible primary-interface estimate in the two REBLUR
                    // lanes so the transmitted lane remains a complete RR transparency guide.
                    primeAccumulate(
                            result.radiance.diffuse,
                            path.throughput * sun.lighting.diffuse * sun.visibility);
                    primeAccumulate(
                            result.reflectionSpecularRadiance,
                            path.throughput * sun.lighting.specular * sun.visibility);
                } else {
                    result.guides.sunPenumbra = sun.penumbra;
                    result.radiance.sunVisibility = sun.visibility;
                    primeAccumulate(
                            result.radiance.unshadowedSun,
                            path.throughput * (sun.lighting.diffuse + sun.lighting.specular));
                }
                vec3 primaryAreaDiffuse =
                        path.throughput * nonsun.diffuse;
                vec3 primaryAreaSpecular =
                        path.throughput * nonsun.specular;
                primeAccumulate(result.radiance.diffuse, primaryAreaDiffuse);
                if (firstTransparent) {
                    primeAccumulate(result.reflectionSpecularRadiance, primaryAreaSpecular);
                } else {
                    primeAccumulate(result.radiance.specular, primaryAreaSpecular);
                }
                result.guides.primaryAreaDiffuse = primaryAreaDiffuse;
                result.guides.primaryAreaSpecular = primaryAreaSpecular;
                if (any(greaterThan(primaryAreaDiffuse, vec3(0.0)))
                        || any(greaterThan(primaryAreaSpecular, vec3(0.0)))) {
                    // SH1 needs the non-sun direct sample apart from the continuation direction.
                    result.guides.primaryAreaDirection = nonsun.direction;
                }
            } else {
                vec3 direct = path.throughput * primeEstimateDirectLighting(
                        integrator,
                        surface,
                        viewDirection,
                        preparedSample,
                        volumeStack);
                primeAccumulateAfterPrimary(result, denoiserState.diffusePath, direct);
            }
        }

        vec3 scatterSample = primeSobolSample3D(
                preparedSample,
                PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                PRIME_SAMPLE_DIMENSION_PRIMARY);

        PrimeDenoiseAlbedos surfaceAlbedos;
        if (firstTransparent) {
            primeSetNumericalContext(PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
            vec3 transmissionSample = primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                    PRIME_SAMPLE_DIMENSION_SECONDARY);
            PrimeTransmissivePrimarySample primarySample =
                    primeSampleMinecraftTransmissionPrimary(
                    surface.baseColor,
                    primeSurfaceOpacity(surface),
                    primeSurfaceOutwardShadingNormal(surface),
                    surface.materialFlags,
                    surface.labPbrNormal,
                    surface.labPbrSpecular,
                    viewDirection,
                    scatterSample,
                    transmissionSample,
                    surface.t,
                    volumeStack);
            surfaceAlbedos = primarySample.albedos;
            PrimeTransmissiveBsdfSplit split = primarySample.paths;
            vec3 primaryShadingNormal =
                    primeSurfaceShadingNormal(surface, viewDirection);
            uint transparentGuideMode = primeTransparentGuideMode();
            bool nrdGuideMode =
                    transparentGuideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_NRD;
            bool rrGuideMode =
                    transparentGuideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR;

            result.guides.primaryDistance = length(
                    surface.position - primePush.cameraPosition);
            result.guides.primaryPosition = surface.position - primePush.cameraPosition;
            result.guides.primaryAlbedo = surfaceAlbedos.diffuse;
            result.guides.primaryNormal = primaryShadingNormal;
            result.guides.primaryHitKind = surface.hitKind;
            result.guides.primaryMaterialFlags = surface.materialFlags;
            result.guides.primarySpecularAlbedo = surfaceAlbedos.specular;
            result.guides.primaryLinearRoughness = surfaceLinearRoughness;
            result.transparentPrimary = true;
            if (nrdGuideMode || rrGuideMode) {
                primeSetPsrGuide(
                        result.transmissionGuides,
                        surface,
                        primaryShadingNormal,
                        surfaceLinearRoughness,
                        surfaceAlbedos,
                        path.throughput,
                        primeEmptyPsrDeltaChain());
            }
            if (nrdGuideMode) {
                primeSetPsrGuide(
                        result.reflectionGuides,
                        surface,
                        primaryShadingNormal,
                        surfaceLinearRoughness,
                        surfaceAlbedos,
                        path.throughput,
                        primeEmptyPsrDeltaChain());
            }

            BsdfSample reflected = split.reflection.bsdfSample;
            BsdfSample transmitted = split.transmission.bsdfSample;

            if (primeHasScatter(reflected)) {
                primeRecordDirection(reflected.direction);
                primeRecordNonnegative(reflected.response);
                primeRecordNonnegative(reflected.pdf);
                primeRecordNonnegative(reflected.relativeEta);
                result.guides.specularDirection = reflected.direction;
                if (nrdGuideMode) {
                    result.reflectionGuides.specularDirection = reflected.direction;
                }
                PathState reflectionPath = path;
                if (primeAdvancePath(
                        reflectionPath,
                        surface,
                        reflected,
                        preparedSample,
                        pureDeltaInterface)) {
                    reflectionPath.bounce = path.bounce + 1u;
                    if (nrdGuideMode) {
                        PrimeTransparentBranchResult reflection =
                                primeIntegrateTransparentBranch(
                                reflectionPath,
                                integrator,
                                split.reflection.volumeStack,
                                surface,
                                viewDirection,
                                primaryShadingNormal,
                                surfaceLinearRoughness,
                                surfaceAlbedos,
                                path.throughput,
                                reflected,
                                false);
                        primeAccumulate(
                                result.reflectionDiffuseRadiance,
                                reflection.diffuseRadiance);
                        primeAccumulate(
                                result.reflectionSpecularRadiance,
                                reflection.specularRadiance);
                        result.guides.specularHitDistance = reflection.firstHitDistance;
                        result.reflectionGuides = reflection.guides;
                        result.reflectionDirectionalGuide = reflection.directionalGuide;
                    } else {
                        PrimeContinuationResult reflection = primeIntegrateContinuation(
                                reflectionPath,
                                integrator,
                                split.reflection.volumeStack);
                        primeAccumulate(
                                result.reflectionSpecularRadiance,
                                reflection.radiance);
                        if (rrGuideMode) {
                            result.guides.specularHitDistance =
                                    reflection.firstHitDistance;
                        }
                    }
                }
            }
            if (primeHasScatter(transmitted)) {
                primeRecordDirection(transmitted.direction);
                primeRecordNonnegative(transmitted.response);
                primeRecordNonnegative(transmitted.pdf);
                primeRecordNonnegative(transmitted.relativeEta);
                result.guides.diffuseDirection = transmitted.direction;
                if (nrdGuideMode || rrGuideMode) {
                    result.transmissionGuides.diffuseDirection = transmitted.direction;
                }
                PathState transmissionPath = path;
                // Branch identity enters the base seed once, making every later light, BSDF and
                // roulette sample independent without adding RNG calls or dimensions.
                transmissionPath.sampleDimension = path.sampleDimension + 1u;
                if (primeAdvancePath(
                        transmissionPath,
                        surface,
                        transmitted,
                        preparedSample,
                        pureDeltaInterface)) {
                    transmissionPath.bounce = path.bounce + 1u;
                    if (nrdGuideMode || rrGuideMode) {
                        PrimeTransparentBranchResult transmission =
                                primeIntegrateTransparentBranch(
                                transmissionPath,
                                integrator,
                                split.transmission.volumeStack,
                                surface,
                                viewDirection,
                                primaryShadingNormal,
                                surfaceLinearRoughness,
                                surfaceAlbedos,
                                path.throughput,
                                transmitted,
                                true);
                        primeAccumulate(
                                result.radiance.diffuse,
                                transmission.diffuseRadiance);
                        primeAccumulate(
                                result.radiance.specular,
                                transmission.specularRadiance);
                        result.guides.diffuseHitDistance = transmission.firstHitDistance;
                        result.transmissionGuides = transmission.guides;
                        result.transmissionAnchorDistance = transmission.anchorDistance;
                    } else {
                        PrimeContinuationResult transmission = primeIntegrateContinuation(
                                transmissionPath,
                                integrator,
                                split.transmission.volumeStack);
                        primeAccumulate(result.radiance.diffuse, transmission.radiance);
                        result.guides.diffuseHitDistance =
                                transmission.firstHitDistance;
                    }
                }
            }
            if (nrdGuideMode || rrGuideMode) {
                result.transmissionGuides.primaryAreaDiffuse =
                        result.guides.primaryAreaDiffuse;
                result.transmissionGuides.primaryAreaDirection =
                        result.guides.primaryAreaDirection;
            }
            if (nrdGuideMode) {
                result.reflectionGuides.primaryAreaSpecular =
                        result.guides.primaryAreaSpecular;
                result.reflectionGuides.primaryAreaDirection =
                        result.guides.primaryAreaDirection;
            }
            break;
        }
        PrimePathScatter scatter;
        if (!denoiserState.reachedNonDelta) {
            // Energy and continuation sampling share one initialized closure. A rejected proposal
            // still leaves the independently evaluated surface guide intact.
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
        bool hasScatter = primeHasScatter(bsdf);
        if (hasScatter) {
            primeRecordDirection(bsdf.direction);
            primeRecordNonnegative(bsdf.response);
            primeRecordNonnegative(bsdf.pdf);
            primeRecordNonnegative(bsdf.relativeEta);
        }
        volumeStack = scatter.volumeStack;
        if (!denoiserState.reachedNonDelta) {
            bool firstDenoiseSurface = !denoiserState.hasPrimarySurface;
            if (firstDenoiseSurface) {
                denoiserState.specularAlbedoProduct += surfaceAlbedos.specular;
                denoiserState.diffuseAlbedoProduct *= surfaceAlbedos.diffuse;
                denoiserState.diffusePath = primeIsNonDeltaSample(bsdf)
                        && (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
                denoiserState.hasPrimarySurface = true;
                denoiserState.primaryBounce = path.bounce;
                result.guides.primaryDistance = length(
                        surface.position - primePush.cameraPosition);
                result.guides.primaryPosition = surface.position - primePush.cameraPosition;
                result.guides.primaryAlbedo = primeSanitizeDenoiseAlbedo(
                        denoiserState.diffuseAlbedoProduct);
                result.guides.primaryNormal = primeSurfaceShadingNormal(surface, viewDirection);
                result.guides.primaryHitKind = surface.hitKind;
                result.guides.primaryMaterialFlags = surface.materialFlags;
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

            bool sampledNonDelta = primeIsNonDeltaSample(bsdf);
            bool surfaceHasNonDeltaLobe = any(greaterThan(
                    surfaceAlbedos.diffuse, vec3(0.0)))
                    || surfaceLinearRoughness > 0.0;
            if (sampledNonDelta || (!hasScatter && surfaceHasNonDeltaLobe)) {
                if (!firstDenoiseSurface) {
                    denoiserState.specularAlbedoProduct *=
                            surfaceAlbedos.specular + surfaceAlbedos.diffuse;
                }
                denoiserState.reachedNonDelta = true;
                result.guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
                        denoiserState.specularAlbedoProduct);
            } else if (!firstDenoiseSurface && primeIsDeltaSample(bsdf)) {
                denoiserState.specularAlbedoProduct *= surfaceAlbedos.specular;
                result.guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
                        denoiserState.specularAlbedoProduct);
            }
        }
        if (!hasScatter) {
            break;
        }
        if (!primeAdvancePath(
                path, surface, bsdf, preparedSample, pureDeltaInterface)) {
            break;
        }
    }
    return result;
}

PrimeIntegrationResult primeIntegrate(PathState path, IntegratorRecord integrator) {
    PrimeRcVolumeStack volumeStack = (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
    return primeIntegrateWithVolume(path, integrator, volumeStack);
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
    state.reachedNonDelta = false;
    state.diffuseAlbedoProduct = vec3(1.0);
    state.specularAlbedoProduct = vec3(0.0);
    state.diffusePath = false;
    state.primaryBounce = 0u;
    return state;
}

// Processes exactly one ordinary realtime path vertex. Primary transparent interfaces remain a
// local island because their two physical branches and PSR records share one output pixel. Every
// other continuation can cross this boundary without carrying invocation-local BSDF state.
bool primeIntegrateWavefrontSurface(
        inout PathState path,
        IntegratorRecord integrator,
        inout PrimeRcVolumeStack volumeStack,
        inout PrimeDenoiserState denoiserState,
        inout PrimeIntegrationResult result,
        SurfaceInteraction surface,
        bool sunOnlyTail) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_SURFACE, path.bounce);
    primeRecordNonFinite(surface.position);
    primeRecordNonnegative(surface.t);
    primeRecordDirection(surface.geometricNormal);
    primeRecordUnit(surface.baseColor);
    if (!primeKnownHitKind(surface)) {
        return false;
    }

    vec3 viewDirection = -path.rayDirection;
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

    // Emission belongs to the sampling policy of the previous vertex. The last wavefront round
    // still resolves that one pending area-light MIS query, then marks its continuation so every
    // vertex physically executed by the tail can use the radiance-only emitter path.
    bool previousSkippedAreaNee =
            (path.flags & PRIME_PATH_PREVIOUS_NO_AREA_NEE) != 0u;
    vec3 emitted = previousSkippedAreaNee
            ? primeEvaluateHitEmissionWithoutAreaNee(path, surface)
            : primeEvaluateHitEmission(path, surface);
    if (!denoiserState.hasPrimarySurface) {
        primeAccumulate(result.radiance.stable, emitted);
    } else {
        primeAccumulateAfterPrimary(result, denoiserState.diffusePath, emitted);
    }

    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    bool transmissive = primeMaterialIsTransmissive(surface.materialFlags);
    float surfaceLinearRoughness =
            (transmissive || !denoiserState.reachedNonDelta)
            ? primeSurfaceLinearRoughness(surface)
            : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    bool pureDeltaInterface = transmissive && surfaceLinearRoughness == 0.0;
    if (!pureDeltaInterface) {
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
            PrimeDirectLightingSplit nonsun = primeEstimatePrimaryNonsunDirect(
                    surface,
                    viewDirection,
                    preparedSample,
                    volumeStack,
                    false);
            result.guides.sunPenumbra = sun.penumbra;
            result.radiance.sunVisibility = sun.visibility;
            primeAccumulate(
                    result.radiance.unshadowedSun,
                    path.throughput * (sun.lighting.diffuse + sun.lighting.specular));
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
            vec3 direct;
            if (sunOnlyTail) {
                direct = path.throughput * primeEstimateDirectSun(
                        integrator,
                        surface,
                        viewDirection,
                        primeSobolSample2D(
                                preparedSample,
                                PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                                PRIME_SAMPLE_DIMENSION_PRIMARY),
                        volumeStack);
            } else {
                direct = path.throughput * primeEstimateDirectLighting(
                        integrator,
                        surface,
                        viewDirection,
                        preparedSample,
                        volumeStack);
            }
            primeAccumulateAfterPrimary(result, denoiserState.diffusePath, direct);
        }
    }

    vec3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimePathScatter scatter;
    PrimeDenoiseAlbedos surfaceAlbedos;
    if (!denoiserState.reachedNonDelta) {
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
    bool hasScatter = primeHasScatter(bsdf);
    if (hasScatter) {
        primeRecordDirection(bsdf.direction);
        primeRecordNonnegative(bsdf.response);
        primeRecordNonnegative(bsdf.pdf);
        primeRecordNonnegative(bsdf.relativeEta);
    }
    volumeStack = scatter.volumeStack;

    if (!denoiserState.reachedNonDelta) {
        bool firstDenoiseSurface = !denoiserState.hasPrimarySurface;
        if (firstDenoiseSurface) {
            denoiserState.specularAlbedoProduct += surfaceAlbedos.specular;
            denoiserState.diffuseAlbedoProduct *= surfaceAlbedos.diffuse;
            denoiserState.diffusePath = primeIsNonDeltaSample(bsdf)
                    && (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
            denoiserState.hasPrimarySurface = true;
            denoiserState.primaryBounce = path.bounce;
            result.guides.primaryDistance = length(
                    surface.position - primePush.cameraPosition);
            result.guides.primaryPosition = surface.position - primePush.cameraPosition;
            result.guides.primaryAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.diffuseAlbedoProduct);
            result.guides.primaryNormal =
                    primeSurfaceShadingNormal(surface, viewDirection);
            result.guides.primaryHitKind = surface.hitKind;
            result.guides.primaryMaterialFlags = surface.materialFlags;
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

        bool sampledNonDelta = primeIsNonDeltaSample(bsdf);
        bool surfaceHasNonDeltaLobe = any(greaterThan(
                surfaceAlbedos.diffuse, vec3(0.0)))
                || surfaceLinearRoughness > 0.0;
        if (sampledNonDelta || (!hasScatter && surfaceHasNonDeltaLobe)) {
            if (!firstDenoiseSurface) {
                denoiserState.specularAlbedoProduct *=
                        surfaceAlbedos.specular + surfaceAlbedos.diffuse;
            }
            denoiserState.reachedNonDelta = true;
            result.guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.specularAlbedoProduct);
        } else if (!firstDenoiseSurface && primeIsDeltaSample(bsdf)) {
            denoiserState.specularAlbedoProduct *= surfaceAlbedos.specular;
            result.guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.specularAlbedoProduct);
        }
    }
    if (!hasScatter
            || !primeAdvancePath(
                    path, surface, bsdf, preparedSample, pureDeltaInterface)) {
        return false;
    }
    if (sunOnlyTail) {
        path.flags |= PRIME_PATH_PREVIOUS_NO_AREA_NEE;
    }
    path.bounce++;
    return true;
}

PrimeReferenceResult primeIntegrateReferenceWithVolume(
        PathState path,
        IntegratorRecord integrator,
        PrimeRcVolumeStack volumeStack) {
    PrimeReferenceResult result;
    result.radiance = vec3(0.0);
    result.primaryDistance = -1.0;
    uint maximumBounces = min(primePush.path.z & 0xffffu, 256u);
    if (maximumBounces == 0u) {
        return result;
    }

    path.bounce = 0u;
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_TRACE, 0u);
    SurfaceInteraction surface = primeTraceSurface(path.traceOrigin, path.rayDirection);
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_SURFACE, 0u);
    primeRecordNonFinite(surface.position);
    primeRecordNonnegative(surface.t);
    primeRecordDirection(surface.geometricNormal);
    primeRecordUnit(surface.baseColor);
    if (!primeKnownHitKind(surface)) {
        return result;
    }
    if (surface.hitKind == PRIME_HIT_NONE) {
        primeAccumulate(
                result.radiance,
                primeEvaluateEnvironmentContribution(path, integrator));
        return result;
    }
    if (!primeApplySegmentMedium(path, surface, volumeStack)) {
        return result;
    }
    result.primaryDistance = length(surface.position - primePush.cameraPosition);

    vec3 viewDirection = -path.rayDirection;
    primeAccumulate(result.radiance, primeEvaluateHitEmission(path, surface));
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, 1u));
    bool pureDeltaInterface = primeIsPureDeltaInterface(surface);
    bool splitTransparent = primeMaterialIsTransmissive(surface.materialFlags);
    if (!pureDeltaInterface) {
        primeSetNumericalContext(PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, 0u);
        PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
                integrator,
                surface,
                viewDirection,
                primeSobolSample2D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                        PRIME_SAMPLE_DIMENSION_PRIMARY),
                volumeStack,
                splitTransparent);
        PrimeDirectLightingSplit nonsun = primeEstimatePrimaryNonsunDirect(
                surface,
                viewDirection,
                preparedSample,
                volumeStack,
                splitTransparent);
        primeAccumulate(
                result.radiance,
                path.throughput * (
                        (sun.lighting.diffuse + sun.lighting.specular) * sun.visibility
                                + nonsun.diffuse + nonsun.specular));
    }

    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, 0u);
    vec3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    if (splitTransparent) {
        PrimeTransmissiveBsdfSplit split = primeSampleMinecraftTransmissionSplit(
                surface.baseColor,
                primeSurfaceOpacity(surface),
                primeSurfaceOutwardShadingNormal(surface),
                surface.materialFlags,
                surface.labPbrNormal,
                surface.labPbrSpecular,
                viewDirection,
                scatterSample,
                primeSobolSample3D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                        PRIME_SAMPLE_DIMENSION_SECONDARY),
                surface.t,
                volumeStack);

        BsdfSample reflected = split.reflection.bsdfSample;
        if (primeHasScatter(reflected)) {
            primeRecordDirection(reflected.direction);
            primeRecordNonnegative(reflected.response);
            primeRecordNonnegative(reflected.pdf);
            primeRecordNonnegative(reflected.relativeEta);
            PathState reflectionPath = path;
            if (primeAdvancePath(
                    reflectionPath,
                    surface,
                    reflected,
                    preparedSample,
                    pureDeltaInterface)) {
                reflectionPath.bounce = 1u;
                primeAccumulate(
                        result.radiance,
                        primeIntegrateContinuation(
                                reflectionPath,
                                integrator,
                                split.reflection.volumeStack).radiance);
            }
        }

        BsdfSample transmitted = split.transmission.bsdfSample;
        if (primeHasScatter(transmitted)) {
            primeRecordDirection(transmitted.direction);
            primeRecordNonnegative(transmitted.response);
            primeRecordNonnegative(transmitted.pdf);
            primeRecordNonnegative(transmitted.relativeEta);
            PathState transmissionPath = path;
            transmissionPath.sampleDimension = path.sampleDimension + 1u;
            if (primeAdvancePath(
                    transmissionPath,
                    surface,
                    transmitted,
                    preparedSample,
                    pureDeltaInterface)) {
                transmissionPath.bounce = 1u;
                primeAccumulate(
                        result.radiance,
                        primeIntegrateContinuation(
                                transmissionPath,
                                integrator,
                                split.transmission.volumeStack).radiance);
            }
        }
        return result;
    }

    PrimePathScatter scatter = primeSamplePathSurface(
            surface, viewDirection, scatterSample, volumeStack);
    BsdfSample bsdf = scatter.bsdf;
    if (primeHasScatter(bsdf)) {
        primeRecordDirection(bsdf.direction);
        primeRecordNonnegative(bsdf.response);
        primeRecordNonnegative(bsdf.pdf);
        primeRecordNonnegative(bsdf.relativeEta);
        if (primeAdvancePath(
                path, surface, bsdf, preparedSample, pureDeltaInterface)) {
            path.bounce = 1u;
            primeAccumulate(
                    result.radiance,
                    primeIntegrateContinuation(
                            path, integrator, scatter.volumeStack).radiance);
        }
    }
    return result;
}

PrimeReferenceResult primeIntegrateReference(
        PathState path,
        IntegratorRecord integrator) {
    PrimeRcVolumeStack volumeStack = (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
    return primeIntegrateReferenceWithVolume(path, integrator, volumeStack);
}

#endif
