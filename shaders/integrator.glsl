#ifndef PRIME_INTEGRATOR_GLSL
#define PRIME_INTEGRATOR_GLSL

// The entire estimator operates in linear Rec.2020 D65. Scheduling changes may move this state
// between kernels, but they must not reinterpret it as encoded sRGB or another RGB basis.

const uint PRIME_HIT_NONE = 0u;
const uint PRIME_HIT_SURFACE = 1u;
const uint PRIME_PATH_PREVIOUS_DELTA = 1u;

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
    surface.reserved0 = 0u;
    surface.reserved1 = 0u;
    return surface;
}

bool primeVisible(vec3 physicalPosition, vec3 normal, LightSample light) {
    // Shadow traversal uses a one-word payload and never invokes closest-hit. Cutout any-hit still
    // runs and may ignore transparent texels; an accepted intersection leaves the sentinel set.
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

vec3 primeEstimateDirectSun(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec2 sampleValue) {
    LightSample light = primeSampleSun(integrator, surface.position, sampleValue);
    BsdfEvaluation bsdf = primeEvaluateDefaultBsdf(
            surface.baseColor, surface.geometricNormal, viewDirection, light.direction);
    float cosine = max(dot(surface.geometricNormal, light.direction), 0.0);
    if (cosine <= 0.0 || light.pdf <= 0.0
            || !primeVisible(surface.position, surface.geometricNormal, light)) {
        return vec3(0.0);
    }
    float misWeight = primePowerHeuristic(light.pdf, bsdf.pdf);
    return light.radiance * bsdf.value * (cosine * misWeight / light.pdf);
}

vec3 primeEstimateDirectAreaLight(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 treeSample,
        vec2 positionSample) {
    LightSample light = primeSampleAreaLight(surface.position, treeSample, positionSample);
    if (!(light.pdf > 0.0) || all(lessThanEqual(light.radiance, vec3(0.0)))) {
        return vec3(0.0);
    }
    BsdfEvaluation bsdf = primeEvaluateDefaultBsdf(
            surface.baseColor, surface.geometricNormal, viewDirection, light.direction);
    float cosine = max(dot(surface.geometricNormal, light.direction), 0.0);
    if (cosine <= 0.0
            || !primeVisible(surface.position, surface.geometricNormal, light)) {
        return vec3(0.0);
    }
    float misWeight = primePowerHeuristic(light.pdf, bsdf.pdf);
    return light.radiance * bsdf.value * (cosine * misWeight / light.pdf);
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

vec3 primeIntegrate(PathState path, IntegratorRecord integrator, out float primaryDistance) {
    primaryDistance = -1.0;
    uint maximumBounces = min(primePush.path.z, 256u);
    uint rouletteStart = primePush.path.w;
    for (path.bounce = 0u; path.bounce < maximumBounces; ++path.bounce) {
        SurfaceInteraction surface = primeTraceSurface(path.traceOrigin, path.rayDirection);
        if (path.bounce == 0u && surface.hitKind != PRIME_HIT_NONE) {
            primaryDistance = surface.t;
        }
        if (surface.hitKind == PRIME_HIT_NONE) {
            LightEvaluation sun = primeEvaluateSun(
                    integrator, path.physicalOrigin, path.rayDirection);
            bool cannotUseMis = path.bounce == 0u
                    || (path.flags & PRIME_PATH_PREVIOUS_DELTA) != 0u;
            float sunWeight = cannotUseMis
                    ? 1.0
                    : primePowerHeuristic(path.previousBsdfPdf, sun.pdf);
            path.radiance += path.throughput
                    * (primeEnvironmentRadiance(integrator, path.rayDirection)
                    + sun.radiance * sunWeight);
            break;
        }

        LightEvaluation hitAreaLight = primeEvaluateAreaLight(
                surface, path.physicalOrigin, path.rayDirection);
        bool cannotUseHitMis = path.bounce == 0u
                || (path.flags & PRIME_PATH_PREVIOUS_DELTA) != 0u;
        float hitAreaWeight = cannotUseHitMis
                ? 1.0
                : primePowerHeuristic(path.previousBsdfPdf, hitAreaLight.pdf);
        path.radiance += path.throughput * hitAreaLight.radiance * hitAreaWeight;

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

        path.radiance += path.throughput
                * primeEstimateDirectSun(integrator, surface, viewDirection, sunSample);
        path.radiance += path.throughput * primeEstimateDirectAreaLight(
                surface, viewDirection, areaTreeSample, areaPositionSample);

        BsdfSample bsdf = primeSampleDefaultBsdf(
                surface.baseColor, surface.geometricNormal, viewDirection, scatterSample);
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
    return path.radiance;
}

#endif
