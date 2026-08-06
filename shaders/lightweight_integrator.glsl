#ifndef PRIME_LIGHTWEIGHT_INTEGRATOR_GLSL
#define PRIME_LIGHTWEIGHT_INTEGRATOR_GLSL

struct PrimeLightweightSunSample {
    vec3 lighting;
    float penumbra;
    float visibility;
};

vec3 primeEvaluateLightweightDirect(
        SurfaceInteraction surface,
        vec3 viewDirection,
        LightSample light,
        vec3 radiance,
        PrimeRcVolumeStack volumeStack) {
    vec3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    if (!primeDirectSampleEligible(surface, shadingNormal, light)
            || all(lessThanEqual(radiance, vec3(0.0)))) {
        return vec3(0.0);
    }
    if (primeMaterialIsTransmissive(surface.materialFlags)) {
        BsdfEvaluation evaluation = primeEvaluateOfflineMinecraftTransmission(
                surface, viewDirection, light.direction, volumeStack);
        return primeTripleProduct(
                radiance,
                evaluation.response,
                primePowerHeuristicOverPdf(light.pdf, evaluation.pdf));
    }
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

PrimeLightweightSunSample primeEstimateLightweightPrimarySun(
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec2 sampleValue,
        PrimeRcVolumeStack volumeStack) {
    PrimeLightweightSunSample result;
    result.lighting = vec3(0.0);
    result.penumbra = 0.0;
    result.visibility = 0.0;
    LightSample light = primeSampleSun(
            integrator, surface.position, sampleValue);
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
    result.lighting = primeEvaluateLightweightDirect(
            surface, viewDirection, light, radiance, volumeStack);
    return result;
}

vec3 primeEstimateLightweightSun(
        PathState path,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        vec3 viewDirection,
        PrimePreparedSampleBase preparedSample,
        PrimeRcVolumeStack volumeStack) {
    LightSample light = primeSampleSun(
            integrator,
            surface.position,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY));
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
    return path.throughput * primeEvaluateLightweightDirect(
            surface, viewDirection, light, radiance, volumeStack);
}

vec3 primeEvaluateLightweightEnvironment(
        PathState path,
        IntegratorRecord integrator,
        bool previousSunNee) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_EMISSION, path.bounce);
    vec3 stars = primeStarmapRadiance(
            integrator, path.physicalOrigin, path.rayDirection);
    LightEvaluation sun = primeEvaluateSun(
            integrator, path.physicalOrigin, path.rayDirection);
    float sunWeight = previousSunNee && !primePreviousCannotUseMis(path)
            ? primePowerHeuristic(path.previousBsdfPdf, sun.pdf)
            : 1.0;
    return path.throughput
            * (primeEnvironmentRadiance(integrator, path.rayDirection)
                    + stars
                    + sun.radiance * sunWeight);
}

PrimePathScatter primeSampleLightweightSurface(
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
        PrimeRcState state = primeMinecraftTransmissionState(
                surface.baseColor,
                primeSurfaceOpacity(surface),
                primeSurfaceOutwardShadingNormal(surface),
                surface.materialFlags,
                surface.labPbrNormal,
                surface.labPbrSpecular,
                viewDirection,
                surface.t,
                volumeStack);
        PrimeTransmissiveBsdfSample sampled =
                primeSampleOfflineMinecraftTransmissionFromState(
                        state,
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        surface.materialFlags,
                        viewDirection,
                        sampleValue,
                        volumeStack);
        result.bsdf = sampled.bsdfSample;
        result.volumeStack = sampled.volumeStack;
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
                state, shadingNormal, viewDirection, sampleValue, volumeStack);
    }
    return result;
}

void primeSampleLightweightGuidedSurface(
        SurfaceInteraction surface,
        vec3 viewDirection,
        vec3 sampleValue,
        bool sampleTransparentReflection,
        PrimeRcVolumeStack volumeStack,
        out PrimePathScatter scatter,
        out PrimeDenoiseAlbedos albedos) {
    scatter.volumeStack = volumeStack;
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
        scatter.bsdf = primeSampleMinecraftFoliageFromState(
                state, viewDirection, sampleValue, volumeStack);
    } else if (primeMaterialIsTransmissive(surface.materialFlags)) {
        PrimeRcState state = primeMinecraftTransmissionState(
                surface.baseColor,
                primeSurfaceOpacity(surface),
                primeSurfaceOutwardShadingNormal(surface),
                surface.materialFlags,
                surface.labPbrNormal,
                surface.labPbrSpecular,
                viewDirection,
                surface.t,
                volumeStack);
        albedos = primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_TRANSMISSIVE);
        vec3 outwardNormal = primeSurfaceOutwardShadingNormal(surface);
        vec3 localView = primeRcOnbToLocal(
                state.material.geometry.onb, viewDirection);
        PrimeMinecraftMirrorSplit mirror = primeMinecraftMirrorSplit(
                state, localView);
        PrimeTransmissiveBsdfSample sampled;
        if (sampleTransparentReflection) {
            sampled = primeSampleMinecraftTransparentReflectionFromState(
                    state,
                    mirror,
                    outwardNormal,
                    viewDirection,
                    localView,
                    sampleValue,
                    volumeStack);
        } else {
            sampled = primeSampleMinecraftRefractedTransmissionFromState(
                    state,
                    outwardNormal,
                    viewDirection,
                    vec3(1.0) - mirror.reflectance,
                    false,
                    volumeStack);
        }
        sampled.bsdfSample.pdf = primeTransparentCheckerboardPdf(
                sampled.bsdfSample.pdf);
        scatter.bsdf = sampled.bsdfSample;
        scatter.volumeStack = sampled.volumeStack;
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
        scatter.bsdf = primeSampleOpaqueFromState(
                state, shadingNormal, viewDirection, sampleValue, volumeStack);
    }
}

bool primeAdvanceLightweightPath(
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_PATH_ADVANCE, path.bounce);
    vec3 nextThroughput = primeProductOver(
            path.throughput, bsdf.response, bsdf.pdf);
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
    path.previousLightNormal = 0u;
    path.flags = (bsdf.eventFlags & PRIME_BSDF_EVENT_DELTA) != 0u
            ? PRIME_PATH_PREVIOUS_DELTA
            : 0u;
    return true;
}

bool primeIntegrateLightweightSurface(
        inout PathState path,
        IntegratorRecord integrator,
        inout PrimeRcVolumeStack volumeStack,
        inout PrimeDenoiserState denoiserState,
        inout PrimeIntegrationResult result,
        inout bool previousSunNee,
        SurfaceInteraction surface) {
    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_SURFACE, path.bounce);
    primeRecordNonFinite(surface.position);
    primeRecordNonnegative(surface.t);
    primeRecordDirection(surface.geometricNormal);
    primeRecordUnit(surface.baseColor);
    if (!primeKnownHitKind(surface)) {
        return false;
    }
    if (denoiserState.hasPrimarySurface && path.bounce == 1u) {
        float distance = surface.hitKind == PRIME_HIT_NONE
                ? PRIME_NRD_FP16_MAX
                : primeNrdSanitizeHitDistance(surface.t);
        if (denoiserState.diffusePath) {
            result.guides.diffuseHitDistance = distance;
        } else {
            result.guides.specularHitDistance = distance;
        }
    }
    if (surface.hitKind == PRIME_HIT_NONE) {
        vec3 contribution = primeEvaluateLightweightEnvironment(
                path, integrator, previousSunNee);
        if (denoiserState.hasPrimarySurface) {
            primeAccumulateAfterPrimary(
                    result, denoiserState.diffusePath, contribution);
        } else {
            primeAccumulate(result.radiance.stable, contribution);
        }
        return false;
    }
    if (!primeApplySegmentMedium(path, surface, volumeStack)) {
        return false;
    }

    vec3 emitted = primeEvaluateHitEmission(path, surface, false);
    if (denoiserState.hasPrimarySurface) {
        primeAccumulateAfterPrimary(result, denoiserState.diffusePath, emitted);
    } else {
        primeAccumulate(result.radiance.stable, emitted);
    }

    vec3 viewDirection = -path.rayDirection;
    float surfaceRoughness = primeSurfaceLinearRoughness(surface);
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    bool sunNee = primeOfflineHasNonDeltaLobe(
            surface.materialFlags, surface.baseColor, surfaceRoughness);
    if (sunNee) {
        primeSetNumericalContext(
                PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
        if (!denoiserState.hasPrimarySurface) {
            PrimeLightweightSunSample sun = primeEstimateLightweightPrimarySun(
                    integrator,
                    surface,
                    viewDirection,
                    primeSobolSample2D(
                            preparedSample,
                            PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                            PRIME_SAMPLE_DIMENSION_PRIMARY),
                    volumeStack);
            result.guides.sunPenumbra = sun.penumbra;
            result.radiance.sunVisibility = sun.visibility;
            primeAccumulate(
                    result.radiance.unshadowedSun,
                    path.throughput * sun.lighting);
        } else {
            primeAccumulateAfterPrimary(
                    result,
                    denoiserState.diffusePath,
                    primeEstimateLightweightSun(
                            path,
                            integrator,
                            surface,
                            viewDirection,
                            preparedSample,
                            volumeStack));
        }
    }

    primeSetNumericalContext(PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
    vec3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimePathScatter scatter;
    PrimeDenoiseAlbedos albedos;
    bool firstSurface = !denoiserState.hasPrimarySurface;
    if (firstSurface) {
        primeSampleLightweightGuidedSurface(
                surface,
                viewDirection,
                scatterSample,
                primeTransparentCheckerboardSamplesReflection(
                        path.pixel, path.sampleIndex),
                volumeStack,
                scatter,
                albedos);
    } else {
        scatter = primeSampleLightweightSurface(
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
    if (firstSurface) {
        bool transmission = hasScatter
                && (bsdf.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u;
        bool diffuse = hasScatter
                && ((bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u
                        || transmission);
        denoiserState.hasPrimarySurface = true;
        denoiserState.reachedNonDelta = true;
        denoiserState.diffuseAlbedoProduct = albedos.diffuse;
        denoiserState.specularAlbedoProduct = albedos.specular;
        denoiserState.diffusePath = diffuse;
        denoiserState.primaryBounce = 0u;
        result.guides.primaryDistance = length(
                surface.position - primePush.cameraPosition);
        result.guides.primaryPosition =
                surface.position - primePush.cameraPosition;
        result.guides.primaryPreviousPosition =
                primeSurfacePreviousPosition(surface) - primePush.cameraPosition;
        result.guides.primaryHasMotion = primeSurfaceHasMotion(surface);
        result.guides.primaryAlbedo = primeSanitizeDenoiseAlbedo(albedos.diffuse);
        result.guides.primaryNormal =
                primeSurfaceShadingNormal(surface, viewDirection);
        result.guides.primaryHitKind = surface.hitKind;
        result.guides.primaryMaterialFlags = surface.materialFlags;
        result.guides.primarySpecularAlbedo =
                primeSanitizeDenoiseAlbedo(albedos.specular);
        result.guides.primaryLinearRoughness = surfaceRoughness;
        if (hasScatter) {
            if (diffuse) {
                result.guides.diffuseDirection = bsdf.direction;
            } else {
                result.guides.specularDirection = bsdf.direction;
            }
        }
    }
    if (!hasScatter || !primeAdvanceLightweightPath(path, surface, bsdf)) {
        return false;
    }
    previousSunNee = sunNee;
    path.bounce++;
    return true;
}

#endif
