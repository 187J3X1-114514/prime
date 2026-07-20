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
};

struct PrimeIntegrationResult {
    vec3 diffuseRadiance;
    float primaryDistance;
    vec3 specularRadiance;
    float specularHitDistance;
    vec3 stableRadiance;
    float diffuseHitDistance;
    vec3 sunRadiance;
    float sunPenumbra;
    float sunVisibility;
    vec3 primaryAlbedo;
    uint primaryHitKind;
    vec3 primaryNormal;
    uint primaryMaterialFlags;
    vec3 primarySpecularAlbedo;
    float primaryLinearRoughness;
    vec3 primaryPosition;
};

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
    return uintBitsToFloat(primeShadowHitDistanceBits);
}

bool primeVisible(vec3 physicalPosition, vec3 normal, LightSample light) {
    return primeTraceShadowHitDistance(physicalPosition, normal, light) >= PRIME_NRD_FP16_MAX;
}

PrimeDirectLightingSplit primeEvaluateVisibleDirectSplit(
        SurfaceInteraction surface,
        vec3 viewDirection,
        LightSample light,
        vec3 lightRadiance,
        PrimeRcVolumeStack volumeStack) {
    PrimeDirectLightingSplit result;
    result.diffuse = vec3(0.0);
    result.specular = vec3(0.0);
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
        BsdfEvaluation evaluation = primeEvaluateMinecraftTransmission(
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
        result.specular = lightRadiance
                * evaluation.value * (cosine * weightedInversePdf);
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
        vec3 scale = lightRadiance * (cosine * weightedInversePdf);
        result.diffuse = scale * components.diffuseValue;
        result.specular = scale * components.specularValue;
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
    vec3 scale = lightRadiance * (cosine * weightedInversePdf);
    result.diffuse = scale * components.diffuseValue;
    result.specular = scale * components.specularValue;
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
        PrimeRcVolumeStack volumeStack) {
    PrimePrimarySunSample result;
    result.lighting.diffuse = vec3(0.0);
    result.lighting.specular = vec3(0.0);
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
            surface, viewDirection, light, radiance, volumeStack);
    return result;
}

PrimeDirectLightingSplit primeEstimatePrimaryDirectAreaLight(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 treeSample,
        vec2 positionSample,
        PrimeRcVolumeStack volumeStack) {
    AreaLightSample area = primeSampleAreaLight(
            surface.position, treeSample, positionSample);
    if (!primeDirectSampleVisible(surface, viewDirection, area.light)) {
        PrimeDirectLightingSplit result;
        result.diffuse = vec3(0.0);
        result.specular = vec3(0.0);
        return result;
    }
    vec3 radiance = primeResolveSampledAreaLightRadiance(area);
    return primeEvaluateVisibleDirectSplit(
            surface, viewDirection, area.light, radiance, volumeStack);
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
            surface, viewDirection, light, radiance, volumeStack);
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
            surface, viewDirection, area.light, radiance, volumeStack);
    return split.diffuse + split.specular;
}

bool primeRussianRoulette(inout PathState path, uint firstBounce, float sampleValue) {
    if (path.bounce < firstBounce) {
        return true;
    }
    float survival = clamp(max(path.throughput.r, max(path.throughput.g, path.throughput.b)),
            0.05, 0.95);
    if (sampleValue >= survival) {
        return false;
    }
    vec3 survivedThroughput = path.throughput / survival;
    bool valid = !any(isnan(survivedThroughput))
            && !any(isinf(survivedThroughput))
            && all(greaterThanEqual(survivedThroughput, vec3(0.0)))
            && any(greaterThan(survivedThroughput, vec3(0.0)));
    if (valid) {
        path.throughput = survivedThroughput;
    }
    return valid;
}

bool primeTryAccumulate(inout vec3 accumulator, vec3 contribution) {
    bool validContribution = !any(isnan(contribution))
            && !any(isinf(contribution))
            && all(greaterThanEqual(contribution, vec3(0.0)));
    if (!validContribution) {
        return false;
    }
    vec3 candidate = accumulator + contribution;
    bool validCandidate = !any(isnan(candidate))
            && !any(isinf(candidate))
            && all(greaterThanEqual(candidate, vec3(0.0)));
    if (validCandidate) {
        accumulator = candidate;
    }
    return validCandidate;
}

void primeAccumulateAfterPrimary(
        inout PrimeIntegrationResult result,
        bool diffusePath,
        vec3 contribution) {
    if (diffusePath) {
        primeTryAccumulate(result.diffuseRadiance, contribution);
    } else {
        primeTryAccumulate(result.specularRadiance, contribution);
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
    return path.throughput * hitAreaLight.radiance * hitAreaWeight;
}

bool primeApplySegmentMedium(
        inout PathState path,
        SurfaceInteraction surface,
        PrimeRcVolumeStack volumeStack) {
    if (volumeStack.count == 0u) {
        return true;
    }
    PrimeRcVolume medium = volumeStack.values[volumeStack.count - 1u];
    vec3 attenuatedThroughput = path.throughput
            * exp(-medium.extinction * max(surface.t, 0.0));
    bool valid = !any(isnan(attenuatedThroughput))
            && !any(isinf(attenuatedThroughput))
            && all(greaterThanEqual(attenuatedThroughput, vec3(0.0)))
            && any(greaterThan(attenuatedThroughput, vec3(0.0)));
    if (valid) {
        path.throughput = attenuatedThroughput;
    }
    return valid;
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

bool primeValidScatter(BsdfSample bsdf) {
    bool finite = !isnan(bsdf.pdf) && !isinf(bsdf.pdf)
            && !any(isnan(bsdf.weight)) && !any(isinf(bsdf.weight))
            && !any(isnan(bsdf.direction)) && !any(isinf(bsdf.direction));
    return finite
            && bsdf.pdf > 0.0
            && all(greaterThanEqual(bsdf.weight, vec3(0.0)))
            && any(greaterThan(bsdf.weight, vec3(0.0)));
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
        float rouletteSample) {
    vec3 nextThroughput = path.throughput * bsdf.weight;
    bool validThroughput = !any(isnan(nextThroughput))
            && !any(isinf(nextThroughput))
            && all(greaterThanEqual(nextThroughput, vec3(0.0)))
            && any(greaterThan(nextThroughput, vec3(0.0)));
    if (!validThroughput) {
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
    return primeRussianRoulette(
            path, PRIME_RUSSIAN_ROULETTE_START, rouletteSample);
}

PrimeIntegrationResult primeIntegrateWithVolume(
        PathState path,
        IntegratorRecord integrator,
        PrimeRcVolumeStack volumeStack) {
    PrimeIntegrationResult result;
    result.diffuseRadiance = vec3(0.0);
    result.primaryDistance = -1.0;
    result.specularRadiance = vec3(0.0);
    result.specularHitDistance = 0.0;
    result.stableRadiance = vec3(0.0);
    result.diffuseHitDistance = 0.0;
    result.sunRadiance = vec3(0.0);
    result.sunPenumbra = 0.0;
    result.sunVisibility = 0.0;
    result.primaryAlbedo = vec3(0.0);
    result.primaryHitKind = PRIME_HIT_NONE;
    result.primaryNormal = vec3(0.0, 1.0, 0.0);
    result.primaryMaterialFlags = 0u;
    result.primarySpecularAlbedo = vec3(0.0);
    result.primaryLinearRoughness = PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    result.primaryPosition = vec3(0.0);
    bool has_primary_surface = false;
    bool hitted_non_delta = false;
    vec3 albedo = vec3(1.0);
    vec3 specular_albedo = vec3(0.0);
    bool diffusePath = false;
    uint primaryBounce = 0u;
    // This stack is path state, not temporary BSDF state. It must survive every surface bounce so
    // nested air/water/glass transitions use the IOR below the current medium and so absorption is
    // applied exactly once to the segment that was actually travelled.
    // path.z packs three independently generated Java contracts without growing Vulkan's
    // guaranteed 128-byte push range: low 16 bits are the bounce cap, bits 16..30 the exact
    // one-based FSR jitter phase, and bit 31 says that the camera lies inside the water volume.
    uint maximumBounces = min(primePush.path.z & 0xffffu, 256u);
    for (path.bounce = 0u; path.bounce < maximumBounces; ++path.bounce) {
        SurfaceInteraction surface = primeTraceSurface(path.traceOrigin, path.rayDirection);
        vec3 viewDirection = -path.rayDirection;
        if (has_primary_surface && path.bounce == primaryBounce + 1u) {
            float firstBounceHitDistance = surface.hitKind == PRIME_HIT_NONE
                    ? PRIME_NRD_FP16_MAX
                    : max(surface.t, 0.0);
            if (diffusePath) {
                result.diffuseHitDistance = firstBounceHitDistance;
            } else {
                result.specularHitDistance = firstBounceHitDistance;
            }
        }
        if (surface.hitKind == PRIME_HIT_NONE) {
            vec3 contribution = primeEvaluateEnvironmentContribution(path, integrator);
            if (!has_primary_surface) {
                primeTryAccumulate(result.stableRadiance, contribution);
            } else {
                primeAccumulateAfterPrimary(result, diffusePath, contribution);
            }
            break;
        }

        if (!primeApplySegmentMedium(path, surface, volumeStack)) {
            break;
        }

        vec3 emitted = primeEvaluateHitEmission(path, surface);
        if (!has_primary_surface) {
            primeTryAccumulate(result.stableRadiance, emitted);
        } else {
            primeAccumulateAfterPrimary(result, diffusePath, emitted);
        }

        PrimeSampleBase bounceSample = primeMakeSampleBase(path, path.bounce + 1u);
        bool pureDeltaInterface = primeIsPureDeltaInterface(surface);
        if (!pureDeltaInterface) {
            vec2 sunSample = primeSobolSample2D(
                    bounceSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY);
            vec3 areaTreeSample = primeSobolSample3D(
                    bounceSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY);
            vec2 areaPositionSample = primeSobolSample2D(
                    bounceSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_SECONDARY);
            if (!has_primary_surface) {
                PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
                        integrator,
                        surface,
                        viewDirection,
                        sunSample,
                        volumeStack);
                PrimeDirectLightingSplit areaSplit = primeEstimatePrimaryDirectAreaLight(
                        surface,
                        viewDirection,
                        areaTreeSample,
                        areaPositionSample,
                        volumeStack);
                result.sunPenumbra = sun.penumbra;
                result.sunVisibility = sun.visibility;
                primeTryAccumulate(
                        result.sunRadiance,
                        path.throughput * (sun.lighting.diffuse + sun.lighting.specular));
                primeTryAccumulate(
                        result.diffuseRadiance,
                        path.throughput * areaSplit.diffuse);
                primeTryAccumulate(
                        result.specularRadiance,
                        path.throughput * areaSplit.specular);
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
                primeAccumulateAfterPrimary(result, diffusePath, direct);
            }
        }

        vec3 scatterSample = primeSobolSample3D(
                bounceSample,
                PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                PRIME_SAMPLE_DIMENSION_PRIMARY);

        PrimeDenoiseAlbedos surfaceAlbedos;
        if (!hitted_non_delta) {
            // Derive guides from the initialized BSDF before drawing a continuation sample. A
            // rejected proposal must not erase an otherwise valid visible surface.
            surfaceAlbedos = primeSurfaceDenoiseAlbedos(
                    surface, viewDirection, volumeStack);
        }
        PrimePathScatter scatter = primeSamplePathSurface(
                surface, viewDirection, scatterSample, volumeStack);
        BsdfSample bsdf = scatter.bsdf;
        volumeStack = scatter.volumeStack;
        bool validScatter = primeValidScatter(bsdf);
        if (!hitted_non_delta) {
            bool firstDenoiseSurface = !has_primary_surface;
            if (firstDenoiseSurface) {
                specular_albedo += surfaceAlbedos.specular;
                albedo *= surfaceAlbedos.diffuse;
                diffusePath = primeIsNonDeltaSample(bsdf)
                        && (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
                has_primary_surface = true;
                primaryBounce = path.bounce;
                result.primaryDistance = length(surface.position - primePush.cameraPosition);
                result.primaryPosition = surface.position - primePush.cameraPosition;
                result.primaryAlbedo = primeSanitizeDenoiseAlbedo(albedo);
                result.primaryNormal = primeSurfaceShadingNormal(surface, viewDirection);
                result.primaryHitKind = surface.hitKind;
                result.primaryMaterialFlags = surface.materialFlags;
                result.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(specular_albedo);
                result.primaryLinearRoughness = primeSurfaceLinearRoughness(surface);
            }

            bool sampledNonDelta = primeIsNonDeltaSample(bsdf);
            bool surfaceHasFiniteLobe = any(greaterThan(
                    surfaceAlbedos.diffuse, vec3(0.0)))
                    || primeSurfaceLinearRoughness(surface) > 0.0;
            if (sampledNonDelta || (!validScatter && surfaceHasFiniteLobe)) {
                if (!firstDenoiseSurface) {
                    specular_albedo *= surfaceAlbedos.specular + surfaceAlbedos.diffuse;
                }
                hitted_non_delta = true;
                result.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(specular_albedo);
            } else if (!firstDenoiseSurface && primeIsDeltaSample(bsdf)) {
                specular_albedo *= surfaceAlbedos.specular;
                result.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(specular_albedo);
            }
        }
        if (!validScatter) {
            break;
        }
        float rouletteSample = primeHashSample1D(
                bounceSample,
                PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
                PRIME_SAMPLE_DIMENSION_PRIMARY);
        if (!primeAdvancePath(path, surface, bsdf, rouletteSample)) {
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

struct PrimeTransparencyGuideResult {
    vec3 backgroundRadiance;
    float reflectionHitDistance;
    bool valid;
};

// DLSS RR's color-before-transparency guide must remain a real transport signal rather than a
// material mask. For the first visible water/glass interface, force a transmission proposal and
// integrate the rest of that path with the resulting medium stack. The main estimator is untouched;
// this independent path exists only when the RR bit is set and excludes the interface reflection.
PrimeTransparencyGuideResult primeIntegrateTransparencyGuide(
        PathState cameraPath,
        IntegratorRecord integrator) {
    PrimeTransparencyGuideResult guide;
    guide.backgroundRadiance = vec3(0.0);
    guide.reflectionHitDistance = 0.0;
    guide.valid = false;

    SurfaceInteraction primary = primeTraceSurface(cameraPath.traceOrigin, cameraPath.rayDirection);
    if (primary.hitKind == PRIME_HIT_NONE
            || !primeMaterialIsTransmissive(primary.materialFlags)) {
        return guide;
    }

    vec3 viewDirection = -cameraPath.rayDirection;
    vec3 outwardNormal = primeSurfaceOutwardShadingNormal(primary);
    vec3 reflectedDirection = normalize(reflect(cameraPath.rayDirection, outwardNormal));
    SurfaceInteraction reflected = primeTraceSurface(
            primeOffsetRayOrigin(primary.position, primary.geometricNormal, reflectedDirection),
            reflectedDirection);
    guide.reflectionHitDistance = reflected.hitKind == PRIME_HIT_NONE
            ? PRIME_NRD_FP16_MAX
            : max(reflected.t, 0.0);

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
    guide.backgroundRadiance = background.diffuseRadiance
            + background.specularRadiance
            + background.stableRadiance
            + background.sunRadiance * background.sunVisibility;
    guide.valid = !any(isnan(guide.backgroundRadiance))
            && !any(isinf(guide.backgroundRadiance))
            && all(greaterThanEqual(guide.backgroundRadiance, vec3(0.0)));
    return guide;
}

#endif
