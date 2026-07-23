#ifndef PRIME_INTEGRATOR_GLSL
#define PRIME_INTEGRATOR_GLSL

// The entire estimator operates in linear Rec.2020 D65. Scheduling changes may move this state
// between kernels, but they must not reinterpret it as encoded sRGB or another RGB basis.

const uint PRIME_HIT_NONE = 0u;
const uint PRIME_HIT_SURFACE = 1u;
const uint PRIME_PATH_PREVIOUS_DELTA = 1u;
// This path vertex deliberately had no next-event estimate. Its first emitter/environment hit
// therefore has no competing light-sampling technique and must receive MIS weight one.
const uint PRIME_PATH_PREVIOUS_NO_NEE = 2u;

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
    bool transparentPrimary;
};

struct PrimeTransparentBranchResult {
    vec3 diffuseRadiance;
    vec3 specularRadiance;
    PrimeDenoiserGuides guides;
    float firstHitDistance;
    float anchorDistance;
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
#if defined(PRIME_ENABLE_SER)
    // Hit objects decouple traversal from closest-hit/miss execution. The reorder point groups
    // those continuations by shader first and by the low section-index bits second, improving
    // both branch and primitive-buffer locality in incoherent terrain without moving path state
    // into global queues. Keep this helper narrow: every caller-local value live here becomes
    // state that the implementation may need to save and restore across invocation repacking.
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

bool primePreviousCannotUseMis(PathState path) {
    return path.bounce == 0u
            || (path.flags & (PRIME_PATH_PREVIOUS_DELTA | PRIME_PATH_PREVIOUS_NO_NEE)) != 0u;
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

PrimeDenoiseAlbedos primeSurfaceDenoiseAlbedos(
        SurfaceInteraction surface,
        vec3 viewDirection,
        PrimeRcVolumeStack volumeStack) {
    PrimeRcState state;
    uint closureKind;
    if (primeMaterialIsFoliage(surface.materialFlags)) {
        state = primeMinecraftFoliageState(
                surface.baseColor,
                primeSurfaceShadingNormal(surface, viewDirection),
                surface.labPbrNormal,
                surface.labPbrSpecular,
                surface.materialFlags,
                viewDirection,
                surface.t,
                volumeStack);
        closureKind = PRIME_DENOISE_CLOSURE_FOLIAGE;
    } else if (primeMaterialIsTransmissive(surface.materialFlags)) {
        state = primeMinecraftTransmissionState(
                surface.baseColor,
                primeSurfaceOpacity(surface),
                primeSurfaceOutwardShadingNormal(surface),
                surface.materialFlags,
                surface.labPbrNormal,
                surface.labPbrSpecular,
                viewDirection,
                surface.t,
                volumeStack);
        closureKind = PRIME_DENOISE_CLOSURE_TRANSMISSIVE;
    } else {
        state = primeOpaqueState(
                surface.baseColor,
                primeSurfaceShadingNormal(surface, viewDirection),
                surface.labPbrNormal,
                surface.labPbrSpecular,
                surface.materialFlags,
                viewDirection,
                surface.t,
                volumeStack);
        closureKind = PRIME_DENOISE_CLOSURE_OPAQUE;
    }
    return primeDenoiseAlbedosFromState(state, viewDirection, closureKind);
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
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
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
        BsdfEvaluation evaluation = conditionalTransparentBranch
                ? primeEvaluateMinecraftTransmissionBranch(
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        primeSurfaceOutwardShadingNormal(surface),
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
                        primeSurfaceOutwardShadingNormal(surface),
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
        vec3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
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
        vec3 viewDirection,
        LightSample light) {
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    return light.pdf > 0.0
            && (primeMaterialIsTransmissive(surface.materialFlags)
                    || primeMaterialIsFoliage(surface.materialFlags)
                    || dot(shadingNormal, light.direction) > 0.0);
}

bool primeDirectSampleVisible(
        SurfaceInteraction surface,
        vec3 viewDirection,
        LightSample light) {
    return primeDirectSampleEligible(surface, viewDirection, light)
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
    if (!primeDirectSampleEligible(surface, viewDirection, light)) {
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
    if (!primeDirectSampleVisible(surface, viewDirection, area.light)) {
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
    if (!primeDirectSampleVisible(surface, viewDirection, light)) {
        return vec3(0.0);
    }
    vec3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, light);
    PrimeDirectLightingSplit split = primeEvaluateVisibleDirectSplit(
            surface, viewDirection, light, radiance, volumeStack, false);
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
    if (!primeDirectSampleVisible(surface, viewDirection, area.light)) {
        return vec3(0.0);
    }
    vec3 radiance = primeResolveSampledAreaLightRadiance(area);
    PrimeDirectLightingSplit split = primeEvaluateVisibleDirectSplit(
            surface, viewDirection, area.light, radiance, volumeStack, false);
    return split.diffuse + split.specular;
}

bool primeRussianRoulette(
        inout PathState path,
        float sampleValue) {
    float survival = clamp(max(path.throughput.r, max(path.throughput.g, path.throughput.b)),
            0.05, 0.95);
    if (sampleValue >= survival) {
        return false;
    }
    path.throughput /= survival;
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
    LightEvaluation sun = primeEvaluateSun(
            integrator, path.physicalOrigin, path.rayDirection);
    float sunWeight = primePreviousCannotUseMis(path)
            ? 1.0
            : primePowerHeuristic(path.previousBsdfPdf, sun.pdf);
    return path.throughput
            * (primeEnvironmentRadiance(integrator, path.rayDirection)
            + sun.radiance * sunWeight);
}

vec3 primeEvaluateHitEmission(
        PathState path, SurfaceInteraction surface) {
    LightEvaluation hitAreaLight = primeEvaluateAreaLight(
            surface, path.physicalOrigin, path.rayDirection);
    float hitAreaWeight = primePreviousCannotUseMis(path)
            ? 1.0
            : primePowerHeuristic(path.previousBsdfPdf, hitAreaLight.pdf);
    return primeTripleProduct(path.throughput, hitAreaLight.radiance, hitAreaWeight);
}

bool primeApplySegmentMedium(
        inout PathState path,
        SurfaceInteraction surface,
        PrimeRcVolumeStack volumeStack) {
    if (volumeStack.count == 0u) {
        return true;
    }
    PrimeRcVolume medium = volumeStack.values[volumeStack.count - 1u];
    path.throughput *= exp(-medium.extinction * surface.t);
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
        result.bsdf = primeSampleMinecraftFoliage(
                surface.baseColor,
                primeSurfaceShadingNormal(surface, viewDirection),
                surface.labPbrNormal,
                surface.labPbrSpecular,
                surface.materialFlags,
                viewDirection,
                sampleValue,
                surface.t,
                volumeStack);
    } else if (primeMaterialIsTransmissive(surface.materialFlags)) {
        PrimeTransmissiveBsdfSample transmitted = primeSampleMinecraftTransmission(
                surface.baseColor,
                primeSurfaceOpacity(surface),
                primeSurfaceOutwardShadingNormal(surface),
                surface.materialFlags,
                surface.labPbrNormal,
                surface.labPbrSpecular,
                viewDirection,
                sampleValue,
                surface.t,
                volumeStack);
        result.bsdf = transmitted.bsdfSample;
        result.volumeStack = transmitted.volumeStack;
    } else {
        result.bsdf = primeSampleOpaque(
                surface.baseColor,
                primeSurfaceShadingNormal(surface, viewDirection),
                surface.labPbrNormal,
                surface.labPbrSpecular,
                surface.materialFlags,
                viewDirection,
                sampleValue,
                surface.t,
                volumeStack);
    }
    return result;
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
        vec3 viewDirection,
        PrimeDenoiseAlbedos albedos,
        vec3 guideThroughput,
        PrimePsrDeltaChain chain) {
    vec3 position = surface.position - primePush.cameraPosition;
    vec3 normal = primeSurfaceShadingNormal(surface, viewDirection);
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
    guides.primaryLinearRoughness = primeSurfaceLinearRoughness(surface);
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

// Continues one fixed first-interface branch. PSR capture is folded into the existing traversal:
// the first finite surface supplies both material data and the already-required lighting/BSDF
// work, so adding the second REBLUR history performs no guide ray or duplicate material lookup.
PrimeTransparentBranchResult primeIntegrateTransparentBranch(
        PathState path,
        IntegratorRecord integrator,
        PrimeRcVolumeStack volumeStack,
        SurfaceInteraction firstInterface,
        vec3 firstViewDirection,
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
    PrimePsrDeltaChain deltaChain = primeEmptyPsrDeltaChain();
    bool hasGuide = (firstBsdf.eventFlags & PRIME_BSDF_EVENT_DELTA) == 0u;
    bool diffusePath = transmissionBranch;
    uint guideBounce = path.bounce - 1u;
    if (hasGuide) {
        primeSetPsrGuide(
                result.guides,
                firstInterface,
                firstViewDirection,
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
    for (; path.bounce < maximumBounces; ++path.bounce) {
        SurfaceInteraction surface = primeTraceSurface(path.traceOrigin, path.rayDirection);
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
        PrimeSampleBase bounceSample = primeMakeSampleBase(path, path.bounce + 1u);
        PrimePreparedSampleBase preparedSample = primePrepareSampleBase(bounceSample);
        bool pureDeltaInterface = primeIsPureDeltaInterface(surface);
        bool primarySurfaceReplacement = !hasGuide && !pureDeltaInterface;
        PrimeDenoiseAlbedos surfaceAlbedos;
        if (primarySurfaceReplacement) {
            surfaceAlbedos = primeSurfaceDenoiseAlbedos(
                    surface, viewDirection, volumeStack);
            primeSetPsrGuide(
                    result.guides,
                    surface,
                    viewDirection,
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
        }

        primeAccumulateTransparentBranch(
                result,
                primarySurfaceReplacement ? true : diffusePath,
                primeEvaluateHitEmission(path, surface));
        if (!pureDeltaInterface) {
            vec2 sunSample = primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY);
            vec3 areaTreeSample = primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY);
            vec2 areaPositionSample = primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_SECONDARY);
            if (primarySurfaceReplacement) {
                PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
                        integrator,
                        surface,
                        viewDirection,
                        sunSample,
                        volumeStack,
                        false);
                PrimeDirectLightingSplit area = primeEstimatePrimaryDirectAreaLight(
                        surface,
                        viewDirection,
                        areaTreeSample,
                        areaPositionSample,
                        volumeStack,
                        false);
                vec3 diffuseDirect = path.throughput
                        * (sun.lighting.diffuse * sun.visibility + area.diffuse);
                vec3 specularDirect = path.throughput
                        * (sun.lighting.specular * sun.visibility + area.specular);
                primeAccumulate(result.diffuseRadiance, diffuseDirect);
                primeAccumulate(result.specularRadiance, specularDirect);
                result.guides.primaryAreaDiffuse = path.throughput * area.diffuse;
                result.guides.primaryAreaSpecular = path.throughput * area.specular;
                result.guides.primaryAreaDirection = area.direction;
            } else {
                vec3 direct = path.throughput
                        * (primeEstimateDirectSun(
                                integrator,
                                surface,
                                viewDirection,
                                sunSample,
                                volumeStack)
                        + primeEstimateDirectAreaLight(
                                surface,
                                viewDirection,
                                areaTreeSample,
                                areaPositionSample,
                                volumeStack));
                primeAccumulateTransparentBranch(result, diffusePath, direct);
            }
        }

        vec3 scatterSample = primeSobolSample3D(
                preparedSample,
                PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                PRIME_SAMPLE_DIMENSION_PRIMARY);
        PrimePathScatter scatter = primeSamplePathSurface(
                surface, viewDirection, scatterSample, volumeStack);
        BsdfSample bsdf = scatter.bsdf;
        if (bsdf.eventFlags != 0u) {
            primeRecordDirection(bsdf.direction);
        } else {
            primeRecordNonFinite(bsdf.direction);
        }
        primeRecordNonnegative(bsdf.response);
        primeRecordNonnegative(bsdf.pdf);
        primeRecordNonnegative(bsdf.relativeEta);
        if (!primeHasScatter(bsdf)) {
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
        primeSetPsrGuide(
                result.guides,
                firstInterface,
                firstViewDirection,
                firstAlbedos,
                firstGuideThroughput,
                primeEmptyPsrDeltaChain());
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
    // path.z packs three independently generated Java contracts without growing Vulkan's
    // guaranteed 128-byte push range: low 16 bits are the bounce cap, bits 16..30 the exact
    // one-based FSR jitter phase, and bit 31 says that the camera lies inside the water volume.
    uint maximumBounces = min(primePush.path.z & 0xffffu, 256u);
    for (path.bounce = 0u; path.bounce < maximumBounces; ++path.bounce) {
        SurfaceInteraction surface = primeTraceSurface(path.traceOrigin, path.rayDirection);
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

        bool firstTransparent = !denoiserState.hasPrimarySurface
                && primeMaterialIsTransmissive(surface.materialFlags);
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

        PrimeSampleBase bounceSample = primeMakeSampleBase(path, path.bounce + 1u);
        PrimePreparedSampleBase preparedSample = primePrepareSampleBase(bounceSample);
        bool pureDeltaInterface = primeIsPureDeltaInterface(surface);
        if (!pureDeltaInterface) {
            vec2 sunSample = primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY);
            vec3 areaTreeSample = primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY);
            vec2 areaPositionSample = primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_SECONDARY);
            if (!denoiserState.hasPrimarySurface) {
                PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
                        integrator,
                        surface,
                        viewDirection,
                        sunSample,
                        volumeStack,
                        firstTransparent);
                PrimeDirectLightingSplit areaSplit = primeEstimatePrimaryDirectAreaLight(
                        surface,
                        viewDirection,
                        areaTreeSample,
                        areaPositionSample,
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
                vec3 primaryAreaDiffuse = path.throughput * areaSplit.diffuse;
                vec3 primaryAreaSpecular = path.throughput * areaSplit.specular;
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
                    // SH1 needs the primary area sample apart from the continuation direction.
                    result.guides.primaryAreaDirection = areaSplit.direction;
                }
            } else {
                vec3 direct = path.throughput
                        * (primeEstimateDirectSun(
                                integrator,
                                surface,
                                viewDirection,
                                sunSample,
                                volumeStack)
                        + primeEstimateDirectAreaLight(
                                surface,
                                viewDirection,
                                areaTreeSample,
                                areaPositionSample,
                                volumeStack));
                primeAccumulateAfterPrimary(result, denoiserState.diffusePath, direct);
            }
        }

        vec3 scatterSample = primeSobolSample3D(
                preparedSample,
                PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                PRIME_SAMPLE_DIMENSION_PRIMARY);

        PrimeDenoiseAlbedos surfaceAlbedos;
        if (!denoiserState.reachedNonDelta && !firstTransparent) {
            // Derive guides from the initialized BSDF before drawing a continuation sample. An
            // empty proposal must not erase an otherwise valid visible surface.
            surfaceAlbedos = primeSurfaceDenoiseAlbedos(
                    surface, viewDirection, volumeStack);
        }
        if (firstTransparent) {
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

            result.guides.primaryDistance = length(
                    surface.position - primePush.cameraPosition);
            result.guides.primaryPosition = surface.position - primePush.cameraPosition;
            result.guides.primaryAlbedo = surfaceAlbedos.diffuse;
            result.guides.primaryNormal = primeSurfaceShadingNormal(surface, viewDirection);
            result.guides.primaryHitKind = surface.hitKind;
            result.guides.primaryMaterialFlags = surface.materialFlags;
            result.guides.primarySpecularAlbedo = surfaceAlbedos.specular;
            result.guides.primaryLinearRoughness = primeSurfaceLinearRoughness(surface);
            result.transparentPrimary = true;
            primeSetPsrGuide(
                    result.transmissionGuides,
                    surface,
                    viewDirection,
                    surfaceAlbedos,
                    path.throughput,
                    primeEmptyPsrDeltaChain());
            primeSetPsrGuide(
                    result.reflectionGuides,
                    surface,
                    viewDirection,
                    surfaceAlbedos,
                    path.throughput,
                    primeEmptyPsrDeltaChain());

            BsdfSample reflected = split.reflection.bsdfSample;
            BsdfSample transmitted = split.transmission.bsdfSample;
            primeRecordNonnegative(reflected.response);
            primeRecordNonnegative(reflected.pdf);
            primeRecordNonnegative(reflected.relativeEta);
            primeRecordNonnegative(transmitted.response);
            primeRecordNonnegative(transmitted.pdf);
            primeRecordNonnegative(transmitted.relativeEta);

            if (primeHasScatter(reflected)) {
                primeRecordDirection(reflected.direction);
                result.guides.specularDirection = reflected.direction;
                result.reflectionGuides.specularDirection = reflected.direction;
                PathState reflectionPath = path;
                if (primeAdvancePath(
                        reflectionPath,
                        surface,
                        reflected,
                        preparedSample,
                        pureDeltaInterface)) {
                    reflectionPath.bounce = path.bounce + 1u;
                    PrimeTransparentBranchResult reflection =
                            primeIntegrateTransparentBranch(
                            reflectionPath,
                            integrator,
                            split.reflection.volumeStack,
                            surface,
                            viewDirection,
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
                }
            } else {
                primeRecordNonFinite(reflected.direction);
            }
            if (primeHasScatter(transmitted)) {
                primeRecordDirection(transmitted.direction);
                result.guides.diffuseDirection = transmitted.direction;
                result.transmissionGuides.diffuseDirection = transmitted.direction;
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
                    PrimeTransparentBranchResult transmission =
                            primeIntegrateTransparentBranch(
                            transmissionPath,
                            integrator,
                            split.transmission.volumeStack,
                            surface,
                            viewDirection,
                            surfaceAlbedos,
                            path.throughput,
                            transmitted,
                            true);
                    primeAccumulate(result.radiance.diffuse, transmission.diffuseRadiance);
                    primeAccumulate(result.radiance.specular, transmission.specularRadiance);
                    result.guides.diffuseHitDistance = transmission.firstHitDistance;
                    result.transmissionGuides = transmission.guides;
                    result.transmissionAnchorDistance = transmission.anchorDistance;
                }
            } else {
                primeRecordNonFinite(transmitted.direction);
            }
            result.transmissionGuides.primaryAreaDiffuse =
                    result.guides.primaryAreaDiffuse;
            result.transmissionGuides.primaryAreaDirection =
                    result.guides.primaryAreaDirection;
            result.reflectionGuides.primaryAreaSpecular =
                    result.guides.primaryAreaSpecular;
            result.reflectionGuides.primaryAreaDirection =
                    result.guides.primaryAreaDirection;
            break;
        }
        PrimePathScatter scatter = primeSamplePathSurface(
                surface, viewDirection, scatterSample, volumeStack);
        BsdfSample bsdf = scatter.bsdf;
        if (bsdf.eventFlags != 0u) {
            primeRecordDirection(bsdf.direction);
        } else {
            primeRecordNonFinite(bsdf.direction);
        }
        primeRecordNonnegative(bsdf.response);
        primeRecordNonnegative(bsdf.pdf);
        primeRecordNonnegative(bsdf.relativeEta);
        volumeStack = scatter.volumeStack;
        bool hasScatter = primeHasScatter(bsdf);
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
                result.guides.primaryLinearRoughness = primeSurfaceLinearRoughness(surface);
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
                    || primeSurfaceLinearRoughness(surface) > 0.0;
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

#endif
