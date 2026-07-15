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
    traceRayEXT(primeScene, gl_RayFlagsNoneEXT, 0xff, 0, 1, 0,
            origin, 0.0, direction, 1000000.0, 0);
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
    surface.reserved1 = 0u;
    return surface;
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
        bool primarySplit) {
    PrimeDirectLightingSplit result;
    result.diffuse = vec3(0.0);
    result.specular = vec3(0.0);
    float cosine = max(dot(surface.geometricNormal, light.direction), 0.0);
    if (cosine <= 0.0 || light.pdf <= 0.0
            || all(lessThanEqual(lightRadiance, vec3(0.0)))) {
        return result;
    }
    PrimeDefaultBsdfComponents components = primeEvaluateDefaultBsdfComponents(
            surface.baseColor, surface.geometricNormal, viewDirection, light.direction);
    float specularProbability = primarySplit
            ? primeNrdSpecularSampleProbability(
                    surface.baseColor, viewDirection, surface.geometricNormal)
            : primeDefaultSpecularSampleProbability(
                    surface.baseColor, viewDirection, surface.geometricNormal);
    float bsdfPdf = mix(
            components.diffuse.pdf, components.specular.pdf, specularProbability);
    float misWeight = primePowerHeuristic(light.pdf, bsdfPdf);
    vec3 scale = lightRadiance * (cosine * misWeight / light.pdf);
    result.diffuse = scale * components.diffuse.value;
    result.specular = scale * components.specular.value;
    return result;
}

bool primeDirectSampleVisible(SurfaceInteraction surface, LightSample light) {
    return light.pdf > 0.0
            && dot(surface.geometricNormal, light.direction) > 0.0
            && primeVisible(surface.position, surface.geometricNormal, light);
}

PrimeDirectLightingSplit primeEstimatePrimaryDirectSun(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec2 sampleValue) {
    LightSample light = primeSampleSun(integrator, surface.position, sampleValue);
    if (!primeDirectSampleVisible(surface, light)) {
        PrimeDirectLightingSplit result;
        result.diffuse = vec3(0.0);
        result.specular = vec3(0.0);
        return result;
    }
    vec3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, light);
    return primeEvaluateVisibleDirectSplit(
            surface, viewDirection, light, radiance, true);
}

PrimeDirectLightingSplit primeEstimatePrimaryDirectAreaLight(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 treeSample,
        vec2 positionSample) {
    AreaLightSample area = primeSampleAreaLight(
            surface.position, treeSample, positionSample);
    if (!primeDirectSampleVisible(surface, area.light)) {
        PrimeDirectLightingSplit result;
        result.diffuse = vec3(0.0);
        result.specular = vec3(0.0);
        return result;
    }
    vec3 radiance = primeResolveSampledAreaLightRadiance(area);
    return primeEvaluateVisibleDirectSplit(
            surface, viewDirection, area.light, radiance, true);
}

vec3 primeEstimateDirectSun(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec2 sampleValue) {
    LightSample light = primeSampleSun(integrator, surface.position, sampleValue);
    if (!primeDirectSampleVisible(surface, light)) {
        return vec3(0.0);
    }
    vec3 radiance = primeResolveSampledSunRadiance(
            integrator, surface.position, light);
    PrimeDirectLightingSplit split = primeEvaluateVisibleDirectSplit(
            surface, viewDirection, light, radiance, false);
    return split.diffuse + split.specular;
}

vec3 primeEstimateDirectAreaLight(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 treeSample,
        vec2 positionSample) {
    AreaLightSample area = primeSampleAreaLight(
            surface.position, treeSample, positionSample);
    if (!primeDirectSampleVisible(surface, area.light)) {
        return vec3(0.0);
    }
    vec3 radiance = primeResolveSampledAreaLightRadiance(area);
    PrimeDirectLightingSplit split = primeEvaluateVisibleDirectSplit(
            surface, viewDirection, area.light, radiance, false);
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
    // path.z packs two independently generated Java contracts without growing the guaranteed
    // 128-byte push range: low 16 bits are the bounce cap, high 16 bits the FSR jitter period.
    uint maximumBounces = min(primePush.path.z & 0xffffu, 256u);
    // Push path.w is reserved for FSR's camera-jitter frame index. Russian roulette remains an
    // estimator contract and is deliberately fixed here instead of sharing temporal state.
    uint rouletteStart = 5u;
    for (path.bounce = 0u; path.bounce < maximumBounces; ++path.bounce) {
        SurfaceInteraction surface = primeTraceSurface(path.traceOrigin, path.rayDirection);
        if (path.bounce == 0u && surface.hitKind != PRIME_HIT_NONE) {
            result.primaryDistance = surface.t;
            result.primaryBaseColor = surface.baseColor;
            result.primaryNormal = surface.geometricNormal;
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
        vec3 viewDirection = -path.rayDirection;

        if (path.bounce == 0u) {
            PrimeDirectLightingSplit sunSplit = primeEstimatePrimaryDirectSun(
                    integrator, surface, viewDirection, sunSample);
            PrimeDirectLightingSplit areaSplit = primeEstimatePrimaryDirectAreaLight(
                    surface, viewDirection, areaTreeSample, areaPositionSample);
            result.diffuseRadiance += sunSplit.diffuse + areaSplit.diffuse;
            result.specularRadiance += sunSplit.specular + areaSplit.specular;
        } else {
            vec3 direct = path.throughput
                    * (primeEstimateDirectSun(integrator, surface, viewDirection, sunSample)
                    + primeEstimateDirectAreaLight(
                            surface, viewDirection, areaTreeSample, areaPositionSample));
            primeAccumulateAfterPrimary(result, diffusePath, direct);
        }

        BsdfSample bsdf;
        if (path.bounce == 0u) {
            uint selectedLobe;
            bsdf = primeSampleDefaultBsdfSeparated(
                    surface.baseColor,
                    surface.geometricNormal,
                    viewDirection,
                    scatterSample,
                    selectedLobe);
            diffusePath = selectedLobe == PRIME_DEFAULT_LOBE_DIFFUSE;
        } else {
            bsdf = primeSampleDefaultBsdf(
                    surface.baseColor, surface.geometricNormal, viewDirection, scatterSample);
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
