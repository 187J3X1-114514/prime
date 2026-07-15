#ifndef PRIME_INTEGRATOR_GLSL
#define PRIME_INTEGRATOR_GLSL

// The entire estimator operates in linear Rec.2020 D65. Scheduling changes may move this state
// between kernels, but they must not reinterpret it as encoded sRGB or another RGB basis.

const uint PRIME_HIT_NONE = 0u;
const uint PRIME_HIT_SURFACE = 1u;
const uint PRIME_PATH_PREVIOUS_DELTA = 1u;

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

SurfaceInteraction primeTraceSurface(vec3 origin, vec3 direction) {
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
    surface.reserved0 = primePayload.reserved0;
    surface.reserved1 = primePayload.reserved1;
    return surface;
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
        PrimeRcVolumeStack volumeStack) {
    PrimeDirectLightingSplit result;
    result.diffuse = vec3(0.0);
    result.specular = vec3(0.0);
    bool transmissive = primeMaterialIsTransmissive(surface.materialFlags);
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    float cosine = transmissive
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
    PrimeDefaultBsdfComponents components = primeEvaluateDefaultBsdfComponents(
            surface.baseColor, shadingNormal, viewDirection, light.direction);
    float specularProbability = primarySplit
            ? primeNrdSpecularSampleProbability(
                    surface.baseColor, viewDirection, shadingNormal)
            : primeDefaultSpecularSampleProbability(
                    surface.baseColor, viewDirection, shadingNormal);
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
                    || dot(shadingNormal, light.direction) > 0.0)
            && primeVisible(surface.position, surface.geometricNormal, light);
}

PrimeDirectLightingSplit primeEstimatePrimaryDirectSun(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec2 sampleValue,
        PrimeRcVolumeStack volumeStack) {
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
            surface, viewDirection, light, radiance, true, volumeStack);
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
            surface, viewDirection, area.light, radiance, true, volumeStack);
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
            surface, viewDirection, light, radiance, false, volumeStack);
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
            surface, viewDirection, area.light, radiance, false, volumeStack);
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
        SurfaceInteraction surface = primeTraceSurface(path.traceOrigin, path.rayDirection);
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
            bool cannotUseMis = path.bounce == 0u
                    || (path.flags & PRIME_PATH_PREVIOUS_DELTA) != 0u;
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
        bool cannotUseHitMis = path.bounce == 0u
                || (path.flags & PRIME_PATH_PREVIOUS_DELTA) != 0u;
        float hitAreaWeight = cannotUseHitMis
                ? 1.0
                : primePowerHeuristic(path.previousBsdfPdf, hitAreaLight.pdf);
        vec3 emitted = path.throughput * hitAreaLight.radiance * hitAreaWeight;
        if (path.bounce == 0u) {
            result.stableRadiance += emitted;
        } else {
            primeAccumulateAfterPrimary(result, diffusePath, emitted);
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
                    integrator, surface, viewDirection, sunSample, volumeStack);
            PrimeDirectLightingSplit areaSplit = primeEstimatePrimaryDirectAreaLight(
                    surface,
                    viewDirection,
                    areaTreeSample,
                    areaPositionSample,
                    volumeStack);
            result.diffuseRadiance += sunSplit.diffuse + areaSplit.diffuse;
            result.specularRadiance += sunSplit.specular + areaSplit.specular;
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

        BsdfSample bsdf;
        if (primeMaterialIsTransmissive(surface.materialFlags)) {
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
            vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
            uint selectedLobe;
            bsdf = primeSampleDefaultBsdfSeparated(
                    surface.baseColor,
                    shadingNormal,
                    viewDirection,
                    scatterSample,
                    selectedLobe);
            diffusePath = selectedLobe == PRIME_DEFAULT_LOBE_DIFFUSE;
        } else {
            vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
            bsdf = primeSampleDefaultBsdf(
                    surface.baseColor, shadingNormal, viewDirection, scatterSample);
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
