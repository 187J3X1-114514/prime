#ifndef PRIME_INTEGRATOR_GLSL
#define PRIME_INTEGRATOR_GLSL

// The entire estimator operates in linear Rec.2020 D65. Scheduling changes may move this state
// between kernels, but they must not reinterpret it as encoded sRGB or another RGB basis.

const uint PRIME_TRACE_RADIANCE = 0u;
const uint PRIME_TRACE_SHADOW = 1u;
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
    primePayload.traceKind = PRIME_TRACE_RADIANCE;
    traceRayEXT(primeScene, gl_RayFlagsNoneEXT, 0xff, 0, 1, 0,
            origin, 0.0, direction, 1000000.0, 0);
    SurfaceInteraction surface;
    surface.position = primePayload.position;
    surface.t = primePayload.t;
    surface.geometricNormal = primePayload.geometricNormal;
    surface.hitKind = primePayload.hitKind;
    surface.baseColor = primePayload.baseColor;
    surface.materialFlags = primePayload.traceKind;
    return surface;
}

bool primeVisible(vec3 physicalPosition, vec3 normal, LightSample light) {
    primePayload.position = vec3(0.0);
    primePayload.t = 0.0;
    primePayload.geometricNormal = vec3(0.0);
    primePayload.hitKind = PRIME_HIT_NONE;
    primePayload.baseColor = vec3(0.0);
    primePayload.traceKind = PRIME_TRACE_SHADOW;
    vec3 traceOrigin = primeOffsetRayOrigin(physicalPosition, normal, light.direction);
    traceRayEXT(primeScene, gl_RayFlagsTerminateOnFirstHitEXT, 0xff, 0, 1, 0,
            traceOrigin, 0.0, light.direction, light.distance, 0);
    return primePayload.hitKind == PRIME_HIT_NONE;
}

vec3 primeEstimateDirectEnvironment(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        inout PathState path) {
    LightSample light = primeSampleEnvironment(integrator, surface.geometricNormal, path);
    BsdfEvaluation bsdf = primeEvaluateDiffuse(
            surface.baseColor, surface.geometricNormal, light.direction);
    float cosine = max(dot(surface.geometricNormal, light.direction), 0.0);
    if (cosine <= 0.0 || light.pdf <= 0.0
            || !primeVisible(surface.position, surface.geometricNormal, light)) {
        return vec3(0.0);
    }
    float misWeight = primePowerHeuristic(light.pdf, bsdf.pdf);
    return light.radiance * bsdf.value * (cosine * misWeight / light.pdf);
}

vec3 primeEstimateDirectSun(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        inout PathState path) {
    LightSample light = primeSampleSun(integrator, surface.position, path);
    BsdfEvaluation bsdf = primeEvaluateDiffuse(
            surface.baseColor, surface.geometricNormal, light.direction);
    float cosine = max(dot(surface.geometricNormal, light.direction), 0.0);
    if (cosine <= 0.0 || light.pdf <= 0.0
            || !primeVisible(surface.position, surface.geometricNormal, light)) {
        return vec3(0.0);
    }
    float misWeight = primePowerHeuristic(light.pdf, bsdf.pdf);
    return light.radiance * bsdf.value * (cosine * misWeight / light.pdf);
}

bool primeRussianRoulette(inout PathState path, uint firstBounce) {
    if (path.bounce < firstBounce) {
        return true;
    }
    float survival = clamp(max(path.throughput.r, max(path.throughput.g, path.throughput.b)),
            0.05, 0.95);
    if (primeRandom(path) >= survival) {
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
            LightEvaluation environment = primeEvaluateEnvironment(
                    integrator, path.rayDirection, path.previousLightPdf);
            LightEvaluation sun = primeEvaluateSun(
                    integrator, path.physicalOrigin, path.rayDirection);
            bool cannotUseMis = path.bounce == 0u
                    || (path.flags & PRIME_PATH_PREVIOUS_DELTA) != 0u;
            float environmentWeight = cannotUseMis
                    ? 1.0
                    : primePowerHeuristic(path.previousBsdfPdf, environment.pdf);
            float sunWeight = cannotUseMis
                    ? 1.0
                    : primePowerHeuristic(path.previousBsdfPdf, sun.pdf);
            path.radiance += path.throughput
                    * (environment.radiance * environmentWeight
                    + sun.radiance * sunWeight);
            break;
        }

        path.radiance += path.throughput
                * (primeEstimateDirectEnvironment(integrator, surface, path)
                + primeEstimateDirectSun(integrator, surface, path));

        BsdfSample bsdf = primeSampleDiffuse(
                surface.baseColor, surface.geometricNormal, path);
        if (bsdf.pdf <= 0.0 || all(lessThanEqual(bsdf.weight, vec3(0.0)))) {
            break;
        }
        path.throughput *= bsdf.weight;
        path.physicalOrigin = surface.position;
        path.traceOrigin = primeOffsetRayOrigin(
                path.physicalOrigin, surface.geometricNormal, bsdf.direction);
        path.rayDirection = bsdf.direction;
        path.previousBsdfPdf = bsdf.pdf;
        path.previousLightPdf = primeEnvironmentPdf(surface.geometricNormal, bsdf.direction);
        path.flags = bsdf.isDelta != 0u ? PRIME_PATH_PREVIOUS_DELTA : 0u;
        if (!primeRussianRoulette(path, rouletteStart)) {
            break;
        }
    }
    return path.radiance;
}

#endif
