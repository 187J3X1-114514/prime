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
    vec3 primaryBaseColor;
    uint primaryHitKind;
    vec3 primaryNormal;
    uint primaryMaterialFlags;
};

vec3 primeOffsetRayOrigin(vec3 physicalPosition, vec3 normal, vec3 direction) {
    // The physical shading point remains unchanged for BSDF/light/PDF evaluation. Only the
    // traversal origin receives this offset, preventing a geometric epsilon from changing the
    // estimator or a future area-light solid-angle conversion.
    float side = dot(normal, direction) >= 0.0 ? 1.0 : -1.0;
    return physicalPosition + normal * (side * 0.001);
}

SurfaceInteraction primeTraceSurfaceWithSbtOffset(
        vec3 origin, vec3 direction, uint hitGroupOffset) {
    primePayload.position = vec3(0.0);
    primePayload.t = 0.0;
    primePayload.geometricNormal = vec3(0.0, 1.0, 0.0);
    primePayload.hitKind = PRIME_HIT_NONE;
    primePayload.baseColor = vec3(0.0);
    primePayload.traceKind = 0u;
    primePayload.sectionIndex = 0u;
    primePayload.emitterIndex = PRIME_NO_LIGHT_INDEX;
    primePayload.reserved0 = 0u;
    primePayload.reserved1 = 0u;
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
            hitGroupOffset,
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
    traceRayEXT(primeScene, gl_RayFlagsNoneEXT, 0xff, hitGroupOffset, 1, 0,
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
    surface.reserved0 = primePayload.reserved0;
    surface.reserved1 = primePayload.reserved1;
    return surface;
}

SurfaceInteraction primeTraceSurface(vec3 origin, vec3 direction) {
    return primeTraceSurfaceWithSbtOffset(origin, direction, 0u);
}

bool primePreviousCannotUseMis(PathState path) {
    return path.bounce == 0u
            || (path.flags & (PRIME_PATH_PREVIOUS_DELTA | PRIME_PATH_PREVIOUS_NO_NEE)) != 0u;
}

vec3 primeSurfaceShadingNormal(SurfaceInteraction surface, vec3 viewDirection) {
    return dot(surface.geometricNormal, viewDirection) >= 0.0
            ? surface.geometricNormal
            : -surface.geometricNormal;
}

float primeSurfaceOpacity(SurfaceInteraction surface) {
    return clamp(uintBitsToFloat(surface.reserved1), 0.0, 1.0);
}

bool primeVisible(vec3 physicalPosition, vec3 normal, LightSample light) {
    primeShadowOccluded = 1u;
    vec3 traceOrigin = primeOffsetRayOrigin(physicalPosition, normal, light.direction);
    traceRayEXT(
            primeScene,
            gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsSkipClosestHitShaderEXT,
            0xff,
            0,
            1,
            1,
            traceOrigin,
            0.0,
            light.direction,
            light.distance,
            1);
    return primeShadowOccluded == 0u;
}

PrimeDirectLightingSplit primeEvaluateVisibleDirectSplit(
        SurfaceInteraction surface,
        vec3 viewDirection,
        LightSample light,
        vec3 lightRadiance,
        bool primarySplit,
        PrimeRcVolumeStack volumeStack,
        PrimeDefaultBsdfContext defaultContext) {
    PrimeDirectLightingSplit result;
    result.diffuse = vec3(0.0);
    result.specular = vec3(0.0);
    bool transmissive = primeMaterialIsTransmissive(surface.materialFlags);
    bool foliage = primeMaterialIsFoliage(surface.materialFlags);
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    float cosine = transmissive || foliage
            ? abs(dot(surface.geometricNormal, light.direction))
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
                surface.geometricNormal,
                surface.materialFlags,
                viewDirection,
                light.direction,
                0.0,
                volumeStack);
        float misWeight = primePowerHeuristic(light.pdf, evaluation.pdf);
        result.specular = lightRadiance
                * evaluation.value * (cosine * misWeight / light.pdf);
        return result;
    }
    if (foliage) {
        // The foliage closure contains both the dominant rough surface response and its small
        // thin-wall transmission term. Treat the combined high-roughness signal as diffuse for
        // the primary NRD split; sampled indirect events still retain their exact lobe flags.
        BsdfEvaluation evaluation = primeEvaluateMinecraftFoliage(
                surface.baseColor,
                surface.geometricNormal,
                viewDirection,
                light.direction,
                0.0,
                volumeStack);
        float misWeight = primePowerHeuristic(light.pdf, evaluation.pdf);
        result.diffuse = lightRadiance
                * evaluation.value * (cosine * misWeight / light.pdf);
        return result;
    }
    PrimeDefaultBsdfComponents components = primeEvaluateDefaultBsdfComponentsWithContext(
            surface.baseColor,
            shadingNormal,
            viewDirection,
            light.direction,
            defaultContext);
    float specularProbability = primarySplit
            ? primeNrdSpecularSampleProbability(defaultContext)
            : defaultContext.specularProbability;
    float bsdfPdf = mix(
            components.diffuse.pdf, components.specular.pdf, specularProbability);
    float misWeight = primePowerHeuristic(light.pdf, bsdfPdf);
    vec3 scale = lightRadiance * (cosine * misWeight / light.pdf);
    result.diffuse = scale * components.diffuse.value;
    result.specular = scale * components.specular.value;
    return result;
}

bool primeDirectSampleVisible(
        SurfaceInteraction surface,
        vec3 viewDirection,
        LightSample light) {
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    return light.pdf > 0.0
            && (primeMaterialIsTransmissive(surface.materialFlags)
                    || primeMaterialIsFoliage(surface.materialFlags)
                    || dot(shadingNormal, light.direction) > 0.0)
            && primeVisible(surface.position, surface.geometricNormal, light);
}

PrimeDirectLightingSplit primeEstimatePrimaryDirectSun(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec2 sampleValue,
        PrimeRcVolumeStack volumeStack,
        PrimeDefaultBsdfContext defaultContext) {
    LightSample light = primeSampleSun(integrator, surface.position, sampleValue);
    if (!primeDirectSampleVisible(surface, viewDirection, light)) {
        PrimeDirectLightingSplit result;
        result.diffuse = vec3(0.0);
        result.specular = vec3(0.0);
        return result;
    }
    vec3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, light);
    return primeEvaluateVisibleDirectSplit(
            surface, viewDirection, light, radiance, true, volumeStack, defaultContext);
}

PrimeDirectLightingSplit primeEstimatePrimaryDirectAreaLight(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 treeSample,
        vec2 positionSample,
        PrimeRcVolumeStack volumeStack,
        PrimeDefaultBsdfContext defaultContext) {
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
            surface, viewDirection, area.light, radiance, true, volumeStack, defaultContext);
}

vec3 primeEstimateDirectSun(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec2 sampleValue,
        PrimeRcVolumeStack volumeStack,
        PrimeDefaultBsdfContext defaultContext) {
    LightSample light = primeSampleSun(integrator, surface.position, sampleValue);
    if (!primeDirectSampleVisible(surface, viewDirection, light)) {
        return vec3(0.0);
    }
    vec3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, light);
    PrimeDirectLightingSplit split = primeEvaluateVisibleDirectSplit(
            surface, viewDirection, light, radiance, false, volumeStack, defaultContext);
    return split.diffuse + split.specular;
}

vec3 primeEstimateDirectAreaLight(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 treeSample,
        vec2 positionSample,
        PrimeRcVolumeStack volumeStack,
        PrimeDefaultBsdfContext defaultContext) {
    AreaLightSample area = primeSampleAreaLight(
            surface.position, treeSample, positionSample);
    if (!primeDirectSampleVisible(surface, viewDirection, area.light)) {
        return vec3(0.0);
    }
    vec3 radiance = primeResolveSampledAreaLightRadiance(area);
    PrimeDirectLightingSplit split = primeEvaluateVisibleDirectSplit(
            surface, viewDirection, area.light, radiance, false, volumeStack, defaultContext);
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
    path.throughput /= survival;
    return true;
}

void primeAccumulateAfterPrimary(
        inout PrimeIntegrationResult result,
        bool diffusePath,
        vec3 contribution) {
    if (diffusePath) {
        result.diffuseRadiance += contribution;
    } else {
        result.specularRadiance += contribution;
    }
}

struct PrimeContinuationResult {
    vec3 diffuseRadiance;
    float diffuseHitDistance;
    vec3 specularRadiance;
    float specularHitDistance;
    vec3 stableRadiance;
    vec3 guidePosition;
    vec3 guideNormal;
    vec3 guideViewDirection;
    float guideLinearRoughness;
    vec3 guideBaseColor;
    vec3 guideThroughput;
    uint guideMaterialFlags;
    uint hasGuide;
    uint stochasticBeforeGuide;
};

// PSR follows the coherent specular path to the first surface with a finite lobe. The chain is
// invocation-local on purpose: persisting every interface per pixel would add a large full-frame
// bandwidth and memory cost. Eight interfaces cover nested panes, water and ordinary glass while
// keeping register pressure bounded; overflow invalidates temporal history instead of truncating
// the optical path and manufacturing a wrong motion vector.
const uint PRIME_DELTA_CHAIN_CAPACITY = 8u;

struct PrimeDeltaChain {
    // xyz is the render-origin-relative interface point. w is n_out / n_in for transmission and
    // one for reflection. The paired record stores the geometric plane normal and event flags.
    vec4 positionEta[PRIME_DELTA_CHAIN_CAPACITY];
    vec4 normalEvent[PRIME_DELTA_CHAIN_CAPACITY];
    uint count;
    uint overflowed;
};

PrimeDeltaChain primeEmptyDeltaChain() {
    PrimeDeltaChain chain;
    chain.count = 0u;
    chain.overflowed = 0u;
    return chain;
}

void primeAppendDeltaInterface(
        inout PrimeDeltaChain chain,
        SurfaceInteraction surface,
        BsdfSample bsdf) {
    if (chain.count >= PRIME_DELTA_CHAIN_CAPACITY) {
        chain.overflowed = 1u;
        return;
    }
    bool transmission = (bsdf.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u;
    float relativeEta = transmission
            ? max(bsdf.relativeEta, PRIME_BSDF_EPSILON)
            : 1.0;
    chain.positionEta[chain.count] = vec4(surface.position, relativeEta);
    chain.normalEvent[chain.count] = vec4(
            normalize(surface.geometricNormal),
            uintBitsToFloat(bsdf.eventFlags));
    chain.count++;
}

PrimeContinuationResult primeIntegrateContinuation(
        PathState path,
        IntegratorRecord integrator,
        PrimeRcVolumeStack volumeStack,
        inout PrimeDeltaChain deltaChain) {
    PrimeContinuationResult result;
    result.diffuseRadiance = vec3(0.0);
    result.diffuseHitDistance = 0.0;
    result.specularRadiance = vec3(0.0);
    result.specularHitDistance = 0.0;
    result.stableRadiance = vec3(0.0);
    result.guidePosition = vec3(0.0);
    result.guideNormal = vec3(0.0, 1.0, 0.0);
    result.guideViewDirection = vec3(0.0, 0.0, 1.0);
    result.guideLinearRoughness = 1.0;
    result.guideBaseColor = vec3(1.0);
    result.guideThroughput = vec3(1.0);
    result.guideMaterialFlags = 0u;
    result.hasGuide = 0u;
    result.stochasticBeforeGuide = 0u;
    uint maximumBounces = min(primePush.path.z & 0xffffu, 256u);
    const uint rouletteStart = 5u;
    uint guideBounce = 0u;
    bool diffusePath = false;
    for (; path.bounce < maximumBounces; ++path.bounce) {
        SurfaceInteraction surface = primeTraceSurface(path.traceOrigin, path.rayDirection);
        vec3 viewDirection = -path.rayDirection;
        if (result.hasGuide != 0u && path.bounce == guideBounce + 1u) {
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
            LightEvaluation sun = primeEvaluateSun(
                    integrator, path.physicalOrigin, path.rayDirection);
            float sunWeight = primePreviousCannotUseMis(path)
                    ? 1.0
                    : primePowerHeuristic(path.previousBsdfPdf, sun.pdf);
            vec3 contribution = path.throughput
                    * (primeEnvironmentRadiance(integrator, path.rayDirection)
                    + sun.radiance * sunWeight);
            if (result.hasGuide == 0u) {
                result.stableRadiance += contribution;
            } else if (diffusePath) {
                result.diffuseRadiance += contribution;
            } else {
                result.specularRadiance += contribution;
            }
            break;
        }

        if (volumeStack.count > 0u) {
            PrimeRcVolume medium = volumeStack.values[volumeStack.count - 1u];
            path.throughput *= exp(-medium.extinction * max(surface.t, 0.0));
            if (all(lessThanEqual(path.throughput, vec3(0.0)))) {
                break;
            }
        }

        // NRD's primary-surface-replacement contract skips a chain of pure delta interfaces and
        // promotes the first surface with a finite lobe to the virtual G-buffer. The distance from
        // the glass/water interface to this point is primary path length, not NRD hit distance;
        // diffuse/specular hit distance starts with the ray traced *after* this replacement.
        bool pureDeltaInterface = primeMaterialIsTransmissive(surface.materialFlags)
                && primeMaterialLinearRoughness(
                        surface.baseColor, surface.materialFlags) == 0.0;
        bool primarySurfaceReplacement = result.hasGuide == 0u && !pureDeltaInterface;
        if (primarySurfaceReplacement) {
            result.guidePosition = surface.position;
            result.guideNormal = primeSurfaceShadingNormal(surface, viewDirection);
            result.guideViewDirection = viewDirection;
            result.guideLinearRoughness = primeMaterialLinearRoughness(
                    surface.baseColor, surface.materialFlags);
            result.guideBaseColor = surface.baseColor;
            result.guideThroughput = path.throughput;
            result.guideMaterialFlags = surface.materialFlags;
            result.hasGuide = 1u;
            guideBounce = path.bounce;
        }

        LightEvaluation hitAreaLight = primeEvaluateAreaLight(
                surface, path.physicalOrigin, path.rayDirection);
        float hitAreaWeight = primePreviousCannotUseMis(path)
                ? 1.0
                : primePowerHeuristic(path.previousBsdfPdf, hitAreaLight.pdf);
        vec3 emitted = path.throughput * hitAreaLight.radiance * hitAreaWeight;
        if (result.hasGuide == 0u) {
            result.stableRadiance += emitted;
        } else if (primarySurfaceReplacement || diffusePath) {
            // A visible emitter at the replacement surface is deterministic, but keeping it in
            // the diffuse channel avoids another full-resolution branch buffer. Ordinary opaque
            // terrain remains exactly split below and dominates the water-bottom use case.
            result.diffuseRadiance += emitted;
        } else {
            result.specularRadiance += emitted;
        }

        PrimeDefaultBsdfContext defaultContext;
        vec3 defaultShadingNormal;
        if (!primeMaterialIsFoliage(surface.materialFlags)
                && !primeMaterialIsTransmissive(surface.materialFlags)) {
            defaultShadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
            defaultContext = primeMakeDefaultBsdfContext(
                    surface.baseColor, viewDirection, defaultShadingNormal);
        }
        PrimeSampleBase bounceSample = primeMakeSampleBase(path, path.bounce + 1u);
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
        vec3 scatterSample = primeSobolSample3D(
                bounceSample,
                PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                PRIME_SAMPLE_DIMENSION_PRIMARY);
        if (primarySurfaceReplacement) {
            PrimeDirectLightingSplit sunSplit = primeEstimatePrimaryDirectSun(
                    integrator,
                    surface,
                    viewDirection,
                    sunSample,
                    volumeStack,
                    defaultContext);
            PrimeDirectLightingSplit areaSplit = primeEstimatePrimaryDirectAreaLight(
                    surface,
                    viewDirection,
                    areaTreeSample,
                    areaPositionSample,
                    volumeStack,
                    defaultContext);
            result.diffuseRadiance += path.throughput
                    * (sunSplit.diffuse + areaSplit.diffuse);
            result.specularRadiance += path.throughput
                    * (sunSplit.specular + areaSplit.specular);
        } else if (result.hasGuide != 0u) {
            vec3 direct = path.throughput
                    * (primeEstimateDirectSun(
                            integrator,
                            surface,
                            viewDirection,
                            sunSample,
                            volumeStack,
                            defaultContext)
                    + primeEstimateDirectAreaLight(
                            surface,
                            viewDirection,
                            areaTreeSample,
                            areaPositionSample,
                            volumeStack,
                            defaultContext));
            if (diffusePath) {
                result.diffuseRadiance += direct;
            } else {
                result.specularRadiance += direct;
            }
        }

        BsdfSample bsdf;
        if (primeMaterialIsFoliage(surface.materialFlags)) {
            bsdf = primeSampleMinecraftFoliage(
                    surface.baseColor,
                    surface.geometricNormal,
                    viewDirection,
                    scatterSample,
                    surface.t,
                    volumeStack);
            if (primarySurfaceReplacement) {
                diffusePath = (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
            }
        } else if (primeMaterialIsTransmissive(surface.materialFlags)) {
            PrimeTransmissiveBsdfSample transmitted = primeSampleMinecraftTransmission(
                    surface.baseColor,
                    primeSurfaceOpacity(surface),
                    surface.geometricNormal,
                    surface.materialFlags,
                    viewDirection,
                    scatterSample,
                    surface.t,
                    volumeStack);
            bsdf = transmitted.bsdfSample;
            volumeStack = transmitted.volumeStack;
            if (primarySurfaceReplacement) {
                diffusePath = false;
            }
        } else if (primarySurfaceReplacement) {
            uint selectedLobe;
            bsdf = primeSampleDefaultBsdfSeparatedWithContext(
                    surface.baseColor,
                    defaultShadingNormal,
                    viewDirection,
                    scatterSample,
                    selectedLobe,
                    defaultContext);
            diffusePath = selectedLobe == PRIME_DEFAULT_LOBE_DIFFUSE;
        } else {
            bsdf = primeSampleDefaultBsdfWithContext(
                    surface.baseColor,
                    defaultShadingNormal,
                    viewDirection,
                    scatterSample,
                    defaultContext);
        }
        if (bsdf.pdf <= 0.0 || all(lessThanEqual(bsdf.weight, vec3(0.0)))) {
            break;
        }
        if (result.hasGuide == 0u
                && pureDeltaInterface
                && (bsdf.eventFlags & PRIME_BSDF_EVENT_DELTA) != 0u) {
            primeAppendDeltaInterface(deltaChain, surface, bsdf);
        } else if (result.hasGuide == 0u
                && (bsdf.eventFlags & PRIME_BSDF_EVENT_DELTA) == 0u) {
            // Any unsplit choice before a coherent replacement surface can change which surface
            // the branch exposes. Keep the marker for the finite-environment fallback path.
            result.stochasticBeforeGuide = 1u;
        }
        path.throughput *= bsdf.weight;
        path.physicalOrigin = surface.position;
        path.traceOrigin = primeOffsetRayOrigin(
                path.physicalOrigin, surface.geometricNormal, bsdf.direction);
        path.rayDirection = bsdf.direction;
        path.previousBsdfPdf = bsdf.pdf;
        path.flags = (bsdf.eventFlags & PRIME_BSDF_EVENT_DELTA) != 0u
                ? PRIME_PATH_PREVIOUS_DELTA
                : 0u;
        float rouletteSample = primeHashSample1D(
                bounceSample,
                PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
                PRIME_SAMPLE_DIMENSION_PRIMARY);
        if (!primeRussianRoulette(path, rouletteStart, rouletteSample)) {
            break;
        }
    }
    return result;
}

PrimeIntegrationResult primeIntegrate(PathState path, IntegratorRecord integrator) {
    PrimeIntegrationResult result;
    result.diffuseRadiance = vec3(0.0);
    result.primaryDistance = -1.0;
    result.specularRadiance = vec3(0.0);
    result.specularHitDistance = 0.0;
    result.stableRadiance = vec3(0.0);
    result.diffuseHitDistance = 0.0;
    result.primaryBaseColor = vec3(0.0);
    result.primaryHitKind = PRIME_HIT_NONE;
    result.primaryNormal = vec3(0.0, 1.0, 0.0);
    result.primaryMaterialFlags = 0u;
    bool diffusePath = false;
    // This stack is path state, not temporary BSDF state. It must survive every surface bounce so
    // nested air/water/glass transitions use the IOR below the current medium and so absorption is
    // applied exactly once to the segment that was actually travelled.
    PrimeRcVolumeStack volumeStack = (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
    // path.z packs three independently generated Java contracts without growing Vulkan's
    // guaranteed 128-byte push range: low 16 bits are the bounce cap, bits 16..30 the FSR jitter
    // period, and bit 31 says that the camera origin already lies inside the water volume.
    uint maximumBounces = min(primePush.path.z & 0xffffu, 256u);
    // Push path.w is reserved for FSR's camera-jitter frame index. Russian roulette remains an
    // estimator contract and is deliberately fixed here instead of sharing temporal state.
    uint rouletteStart = 5u;
    for (path.bounce = 0u; path.bounce < maximumBounces; ++path.bounce) {
#if defined(PRIME_OPAQUE_PRIMARY_PASS)
        // SBT records 2/3 differ only at the camera vertex: transparent intersections are skipped
        // so NRD receives a coherent opaque guide. Secondary and shadow rays keep the ordinary
        // hit groups and therefore retain all transparent transport instead of treating glass as
        // absent from lighting, which would bias the underlying opaque estimator.
        SurfaceInteraction surface = path.bounce == 0u
                ? primeTraceSurfaceWithSbtOffset(path.traceOrigin, path.rayDirection, 2u)
                : primeTraceSurface(path.traceOrigin, path.rayDirection);
#else
        SurfaceInteraction surface = primeTraceSurface(path.traceOrigin, path.rayDirection);
#endif
        vec3 viewDirection = -path.rayDirection;
        if (path.bounce == 0u && surface.hitKind != PRIME_HIT_NONE) {
            result.primaryDistance = surface.t;
            result.primaryBaseColor = surface.baseColor;
            result.primaryNormal = primeSurfaceShadingNormal(surface, viewDirection);
            result.primaryHitKind = surface.hitKind;
            result.primaryMaterialFlags = surface.materialFlags;
        }
        if (path.bounce == 1u) {
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
            LightEvaluation sun = primeEvaluateSun(
                    integrator, path.physicalOrigin, path.rayDirection);
            bool cannotUseMis = primePreviousCannotUseMis(path);
            float sunWeight = cannotUseMis
                    ? 1.0
                    : primePowerHeuristic(path.previousBsdfPdf, sun.pdf);
            vec3 contribution = path.throughput
                    * (primeEnvironmentRadiance(integrator, path.rayDirection)
                    + sun.radiance * sunWeight);
            if (path.bounce == 0u) {
                result.stableRadiance += contribution;
            } else {
                primeAccumulateAfterPrimary(result, diffusePath, contribution);
            }
            break;
        }

        if (volumeStack.count > 0u) {
            PrimeRcVolume medium = volumeStack.values[volumeStack.count - 1u];
            path.throughput *= exp(-medium.extinction * max(surface.t, 0.0));
            if (all(lessThanEqual(path.throughput, vec3(0.0)))) {
                break;
            }
        }

        LightEvaluation hitAreaLight = primeEvaluateAreaLight(
                surface, path.physicalOrigin, path.rayDirection);
        bool cannotUseHitMis = primePreviousCannotUseMis(path);
        float hitAreaWeight = cannotUseHitMis
                ? 1.0
                : primePowerHeuristic(path.previousBsdfPdf, hitAreaLight.pdf);
        vec3 emitted = path.throughput * hitAreaLight.radiance * hitAreaWeight;
        if (path.bounce == 0u) {
            result.stableRadiance += emitted;
        } else {
            primeAccumulateAfterPrimary(result, diffusePath, emitted);
        }

        PrimeDefaultBsdfContext defaultContext;
        vec3 defaultShadingNormal;
        if (!primeMaterialIsFoliage(surface.materialFlags)
                && !primeMaterialIsTransmissive(surface.materialFlags)) {
            defaultShadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
            defaultContext = primeMakeDefaultBsdfContext(
                    surface.baseColor, viewDirection, defaultShadingNormal);
        }
        PrimeSampleBase bounceSample = primeMakeSampleBase(path, path.bounce + 1u);
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
        vec3 scatterSample = primeSobolSample3D(
                bounceSample,
                PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                PRIME_SAMPLE_DIMENSION_PRIMARY);
        if (path.bounce == 0u) {
            PrimeDirectLightingSplit sunSplit = primeEstimatePrimaryDirectSun(
                    integrator,
                    surface,
                    viewDirection,
                    sunSample,
                    volumeStack,
                    defaultContext);
            PrimeDirectLightingSplit areaSplit = primeEstimatePrimaryDirectAreaLight(
                    surface,
                    viewDirection,
                    areaTreeSample,
                    areaPositionSample,
                    volumeStack,
                    defaultContext);
            result.diffuseRadiance += sunSplit.diffuse + areaSplit.diffuse;
            result.specularRadiance += sunSplit.specular + areaSplit.specular;
        } else {
            vec3 direct = path.throughput
                    * (primeEstimateDirectSun(
                            integrator,
                            surface,
                            viewDirection,
                            sunSample,
                            volumeStack,
                            defaultContext)
                    + primeEstimateDirectAreaLight(
                            surface,
                            viewDirection,
                            areaTreeSample,
                            areaPositionSample,
                            volumeStack,
                            defaultContext));
            primeAccumulateAfterPrimary(result, diffusePath, direct);
        }

        BsdfSample bsdf;
        if (primeMaterialIsFoliage(surface.materialFlags)) {
            bsdf = primeSampleMinecraftFoliage(
                    surface.baseColor,
                    surface.geometricNormal,
                    viewDirection,
                    scatterSample,
                    surface.t,
                    volumeStack);
            if (path.bounce == 0u) {
                diffusePath = (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
            }
        } else if (primeMaterialIsTransmissive(surface.materialFlags)) {
            PrimeTransmissiveBsdfSample transmitted = primeSampleMinecraftTransmission(
                    surface.baseColor,
                    primeSurfaceOpacity(surface),
                    surface.geometricNormal,
                    surface.materialFlags,
                    viewDirection,
                    scatterSample,
                    surface.t,
                    volumeStack);
            bsdf = transmitted.bsdfSample;
            volumeStack = transmitted.volumeStack;
            if (path.bounce == 0u) {
                diffusePath = false;
            }
        } else if (path.bounce == 0u) {
            uint selectedLobe;
            bsdf = primeSampleDefaultBsdfSeparatedWithContext(
                    surface.baseColor,
                    defaultShadingNormal,
                    viewDirection,
                    scatterSample,
                    selectedLobe,
                    defaultContext);
            diffusePath = selectedLobe == PRIME_DEFAULT_LOBE_DIFFUSE;
        } else {
            bsdf = primeSampleDefaultBsdfWithContext(
                    surface.baseColor,
                    defaultShadingNormal,
                    viewDirection,
                    scatterSample,
                    defaultContext);
        }
        if (bsdf.pdf <= 0.0 || all(lessThanEqual(bsdf.weight, vec3(0.0)))) {
            break;
        }
        path.throughput *= bsdf.weight;
        path.physicalOrigin = surface.position;
        path.traceOrigin = primeOffsetRayOrigin(
                path.physicalOrigin, surface.geometricNormal, bsdf.direction);
        path.rayDirection = bsdf.direction;
        path.previousBsdfPdf = bsdf.pdf;
        path.flags = (bsdf.eventFlags & PRIME_BSDF_EVENT_DELTA) != 0u
                ? PRIME_PATH_PREVIOUS_DELTA
                : 0u;
        float rouletteSample = primeHashSample1D(
                bounceSample,
                PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
                PRIME_SAMPLE_DIMENSION_PRIMARY);
        if (!primeRussianRoulette(path, rouletteStart, rouletteSample)) {
            break;
        }
    }
    return result;
}

#endif
