#ifndef PRIME_TRANSPARENT_GUIDE_PROBE_GLSL
#define PRIME_TRANSPARENT_GUIDE_PROBE_GLSL

// These probes are reconstruction observations. They never feed throughput, radiance or transport
// state back into the estimator, so their deterministic choices cannot bias the rendered sample.

struct PrimeTransparentGuideProbeResult {
    PrimeDenoiserGuides transmission;
    PrimeDenoiserGuides reflection;
    float transmissionAnchorDistance;
    vec4 reflectionCurrentVirtualPosition;
    vec4 reflectionPreviousVirtualPosition;
};

PrimeTransparentGuideProbeResult primeEmptyTransparentGuideProbeResult() {
    PrimeTransparentGuideProbeResult result;
    result.transmission = primeEmptyDenoiserGuides();
    result.reflection = primeEmptyDenoiserGuides();
    result.transmissionAnchorDistance = -1.0;
    // Negative w marks an unavailable reflection probe. Zero denotes a finite point and one a
    // direction at infinity. Current geometry feeds NRD; previous geometry feeds RR motion.
    result.reflectionCurrentVirtualPosition = vec4(0.0, 0.0, 0.0, -1.0);
    result.reflectionPreviousVirtualPosition = vec4(0.0, 0.0, 0.0, -1.0);
    return result;
}

vec3 primeMirrorPointAcrossPlane(
        vec3 point, vec3 planePoint, vec3 unitNormal) {
    return point - 2.0 * unitNormal * dot(point - planePoint, unitNormal);
}

vec3 primeMirrorDirectionAcrossPlane(vec3 direction, vec3 unitNormal) {
    return direction - 2.0 * unitNormal * dot(direction, unitNormal);
}

PrimeDenoiseAlbedos primeProbeSurfaceAlbedos(
        SurfaceInteraction surface,
        vec3 viewDirection,
        PrimeRcVolumeStack volumeStack) {
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
        return primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_FOLIAGE);
    }
    if (primeMaterialIsTransmissive(surface.materialFlags)) {
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
        return primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_TRANSMISSIVE);
    }
    PrimeRcState state = primeOpaqueState(
            surface.baseColor,
            primeSurfaceShadingNormal(surface, viewDirection),
            surface.labPbrNormal,
            surface.labPbrSpecular,
            surface.materialFlags,
            viewDirection,
            surface.t,
            volumeStack);
    return primeDenoiseAlbedosFromState(
            state, viewDirection, PRIME_DENOISE_CLOSURE_OPAQUE);
}

bool primeAdvanceProbeThroughput(
        inout vec3 throughput, BsdfSample sampleValue) {
    if (!primeHasScatter(sampleValue)) {
        return false;
    }
    throughput = primeProductOver(
            throughput, sampleValue.response, sampleValue.pdf);
    return primeNrdIsFinite(throughput)
            && any(greaterThan(throughput, vec3(0.0)));
}

void primeSetProbeSurfaceGuide(
        inout PrimeDenoiserGuides guides,
        SurfaceInteraction surface,
        vec3 virtualPosition,
        vec3 previousVirtualPosition,
        vec3 virtualNormal,
        vec3 throughput,
        PrimeDenoiseAlbedos albedos,
        bool hasMotion) {
    guides.primaryDistance = length(virtualPosition);
    guides.primaryPosition = virtualPosition;
    guides.primaryPreviousPosition = previousVirtualPosition;
    guides.primaryHasMotion = hasMotion;
    guides.primaryAlbedo = primeSanitizeDenoiseAlbedo(
            throughput * albedos.diffuse);
    guides.primaryNormal = primeNrdSafeNormalize(
            virtualNormal, vec3(0.0, 1.0, 0.0));
    guides.primaryHitKind = surface.hitKind;
    guides.primaryMaterialFlags = surface.materialFlags;
    guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
            throughput * albedos.specular);
    guides.primaryLinearRoughness = primeSurfaceLinearRoughness(surface);
}

void primeApplyProbeSurfaceGuide(
        inout PrimeDenoiserGuides destination,
        PrimeDenoiserGuides source) {
    if (source.primaryHitKind != PRIME_HIT_SURFACE) {
        return;
    }
    // Hit distances, sampled directions and primary-light moments describe the noisy radiance
    // branch. Only the reconstruction surface identity comes from the independent probe.
    destination.primaryDistance = source.primaryDistance;
    destination.primaryAlbedo = source.primaryAlbedo;
    destination.primaryHitKind = source.primaryHitKind;
    destination.primaryNormal = source.primaryNormal;
    destination.primaryMaterialFlags = source.primaryMaterialFlags;
    destination.primarySpecularAlbedo = source.primarySpecularAlbedo;
    destination.primaryLinearRoughness = source.primaryLinearRoughness;
    destination.primaryPosition = source.primaryPosition;
    destination.primaryPreviousPosition = source.primaryPreviousPosition;
    destination.primaryHasMotion = source.primaryHasMotion;
}

void primeTraceDeterministicTransmissionGuide(
        SurfaceInteraction primarySurface,
        PrimeTransmissiveBsdfSample initialSample,
        uint maximumBounces,
        inout PrimeTransparentGuideProbeResult result) {
    BsdfSample initialBsdf = initialSample.bsdfSample;
    vec3 throughput = vec3(1.0);
    if ((initialBsdf.eventFlags & PRIME_BSDF_EVENT_DELTA) == 0u
            || !primeAdvanceProbeThroughput(throughput, initialBsdf)) {
        return;
    }

    PrimeQueuedPsrState currentState = primeEmptyQueuedPsrState();
    PrimeQueuedPsrState previousState = primeEmptyQueuedPsrState();
    vec3 primaryPreviousPosition = primeSurfacePreviousPosition(primarySurface);
    bool initialReflection =
            (initialBsdf.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u;
    primeAppendQueuedPsrState(
            currentState,
            primePush.cameraPosition,
            primePush.cameraPosition,
            primarySurface.position,
            primarySurface.geometricNormal,
            initialReflection);
    primeAppendQueuedPsrState(
            previousState,
            primePush.cameraPosition,
            primePush.cameraPosition,
            primaryPreviousPosition,
            primarySurface.geometricNormal,
            initialReflection);

    vec3 lastPosition = primarySurface.position;
    vec3 lastPreviousPosition = primaryPreviousPosition;
    vec3 direction = initialBsdf.direction;
    vec3 traceOrigin = primeOffsetRayOrigin(
            primarySurface.position,
            primarySurface.geometricNormal,
            direction);
    PrimeRcVolumeStack volumeStack = initialSample.volumeStack;
    bool hasMotion = primeSurfaceHasMotion(primarySurface);

    [[dont_unroll]]
    for (uint bounce = 1u; bounce < maximumBounces; ++bounce) {
        SurfaceInteraction surface = primeTraceSurfaceWithoutReorder(
                traceOrigin, direction);
        if (!primeKnownHitKind(surface) || surface.hitKind == PRIME_HIT_NONE) {
            return;
        }
        vec3 viewDirection = -direction;
        float roughness = primeSurfaceLinearRoughness(surface);
        if (!primeMaterialIsTransmissive(surface.materialFlags)
                || roughness > 0.0) {
            vec3 currentVirtualPosition;
            vec3 currentVirtualNormal;
            if (!primeBuildQueuedPsrGuideValue(
                    currentState,
                    lastPosition,
                    surface.position,
                    primeSurfaceShadingNormal(surface, viewDirection),
                    currentVirtualPosition,
                    currentVirtualNormal)) {
                return;
            }

            vec3 surfacePreviousPosition = primeSurfacePreviousPosition(surface);
            vec3 previousVirtualPosition;
            vec3 ignoredPreviousNormal;
            bool previousValid = primeBuildQueuedPsrGuideValue(
                    previousState,
                    lastPreviousPosition,
                    surfacePreviousPosition,
                    primeSurfaceShadingNormal(surface, viewDirection),
                    previousVirtualPosition,
                    ignoredPreviousNormal);
            float currentAnchor = primeQueuedPsrAnchorDistanceValue(
                    currentState,
                    primePush.cameraPosition,
                    surface.position,
                    surface.geometricNormal);
            float previousAnchor = previousValid
                    ? primeQueuedPsrAnchorDistanceValue(
                            previousState,
                            primePush.cameraPosition,
                            surfacePreviousPosition,
                            surface.geometricNormal)
                    : -1.0;
            if (currentAnchor > 0.0) {
                currentVirtualPosition = currentState.firstDirectionLength.xyz
                        * currentAnchor;
                if (previousAnchor > 0.0) {
                    previousVirtualPosition =
                            previousState.firstDirectionLength.xyz
                                    * previousAnchor;
                } else {
                    previousValid = false;
                }
            }
            if (!previousValid) {
                previousVirtualPosition = currentVirtualPosition;
            }
            PrimeDenoiseAlbedos albedos = primeProbeSurfaceAlbedos(
                    surface, viewDirection, volumeStack);
            primeSetProbeSurfaceGuide(
                    result.transmission,
                    surface,
                    currentVirtualPosition,
                    previousVirtualPosition,
                    currentVirtualNormal,
                    throughput,
                    albedos,
                    (hasMotion || primeSurfaceHasMotion(surface))
                            && previousValid);
            result.transmissionAnchorDistance = currentAnchor;
            return;
        }

        if (primeQueuedPsrCount(currentState) >= PRIME_QUEUED_PSR_CAPACITY) {
            return;
        }
        PrimeTransmissiveBsdfSample continuation =
                primeSampleMinecraftTransparentContinuation(
                        surface.baseColor,
                        primeSurfaceOpacity(surface),
                        primeSurfaceOutwardShadingNormal(surface),
                        surface.materialFlags,
                        surface.labPbrNormal,
                        surface.labPbrSpecular,
                        viewDirection,
                        surface.t,
                        volumeStack);
        BsdfSample bsdf = continuation.bsdfSample;
        if ((bsdf.eventFlags & PRIME_BSDF_EVENT_DELTA) == 0u
                || !primeAdvanceProbeThroughput(throughput, bsdf)) {
            return;
        }
        vec3 surfacePreviousPosition = primeSurfacePreviousPosition(surface);
        bool reflection =
                (bsdf.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u;
        primeAppendQueuedPsrState(
                currentState,
                primePush.cameraPosition,
                lastPosition,
                surface.position,
                surface.geometricNormal,
                reflection);
        primeAppendQueuedPsrState(
                previousState,
                primePush.cameraPosition,
                lastPreviousPosition,
                surfacePreviousPosition,
                surface.geometricNormal,
                reflection);
        lastPosition = surface.position;
        lastPreviousPosition = surfacePreviousPosition;
        hasMotion = hasMotion || primeSurfaceHasMotion(surface);
        direction = bsdf.direction;
        traceOrigin = primeOffsetRayOrigin(
                surface.position, surface.geometricNormal, direction);
        volumeStack = continuation.volumeStack;
    }
}

void primeTracePlanarReflectionGuide(
        SurfaceInteraction primarySurface,
        PrimeTransmissiveBsdfSample initialSample,
        PrimeRcVolumeStack initialVolumeStack,
        inout PrimeTransparentGuideProbeResult result) {
    BsdfSample bsdf = initialSample.bsdfSample;
    vec3 throughput = vec3(1.0);
    if ((bsdf.eventFlags
            & (PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_DELTA))
                    != (PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_DELTA)
            || !primeAdvanceProbeThroughput(throughput, bsdf)) {
        return;
    }

    vec3 planeNormal = primeNrdSafeNormalize(
            primeSurfaceOutwardShadingNormal(primarySurface),
            primarySurface.geometricNormal);
    SurfaceInteraction target = primeTraceSurfaceWithoutReorder(
            primeOffsetRayOrigin(
                    primarySurface.position,
                    primarySurface.geometricNormal,
                    bsdf.direction),
            bsdf.direction);
    if (!primeKnownHitKind(target)) {
        return;
    }
    if (target.hitKind == PRIME_HIT_NONE) {
        vec3 virtualDirection = primeNrdSafeNormalize(
                primeMirrorDirectionAcrossPlane(bsdf.direction, planeNormal),
                -bsdf.direction);
        result.reflectionCurrentVirtualPosition =
                vec4(virtualDirection, 1.0);
        result.reflectionPreviousVirtualPosition =
                vec4(virtualDirection, 1.0);
        return;
    }

    vec3 primaryPreviousPosition = primeSurfacePreviousPosition(primarySurface);
    vec3 targetPreviousPosition = primeSurfacePreviousPosition(target);
    vec3 currentVirtualPosition = primeMirrorPointAcrossPlane(
            target.position, primarySurface.position, planeNormal)
            - primePush.cameraPosition;
    vec3 previousVirtualPosition = primeMirrorPointAcrossPlane(
            targetPreviousPosition, primaryPreviousPosition, planeNormal)
            - primePush.cameraPosition;
    vec3 targetNormal = primeSurfaceShadingNormal(target, -bsdf.direction);
    vec3 virtualNormal = primeMirrorDirectionAcrossPlane(
            targetNormal, planeNormal);
    PrimeDenoiseAlbedos albedos = primeProbeSurfaceAlbedos(
            target, -bsdf.direction, initialVolumeStack);
    bool hasMotion = primeSurfaceHasMotion(primarySurface)
            || primeSurfaceHasMotion(target);
    primeSetProbeSurfaceGuide(
            result.reflection,
            target,
            currentVirtualPosition,
            hasMotion ? previousVirtualPosition : currentVirtualPosition,
            virtualNormal,
            throughput,
            albedos,
            hasMotion);
    result.reflectionCurrentVirtualPosition = vec4(
            currentVirtualPosition, 0.0);
    result.reflectionPreviousVirtualPosition = vec4(
            hasMotion ? previousVirtualPosition : currentVirtualPosition,
            0.0);
}

PrimeTransparentGuideProbeResult primeTraceTransparentGuideProbes(
        PathState cameraPath,
        SurfaceInteraction primarySurface,
        PrimeRcVolumeStack volumeStack,
        uint maximumBounces) {
    PrimeTransparentGuideProbeResult result =
            primeEmptyTransparentGuideProbeResult();
    if (maximumBounces <= 1u
            || primarySurface.hitKind != PRIME_HIT_SURFACE
            || !primeMaterialIsTransmissive(primarySurface.materialFlags)) {
        return result;
    }

    uint savedFlags = primeRawNumericalFlags;
    uint savedContext = primeRawNumericalContext;
    uint savedFirstContext = primeRawNumericalFirstContext;
    vec3 viewDirection = -cameraPath.rayDirection;
    PrimePreparedSampleBase preparedSample = primePrepareSampleBase(
            primeMakeSampleBase(cameraPath, cameraPath.bounce + 1u));
    PrimeTransmissivePrimarySample primarySample =
            primeSampleMinecraftTransmissionPrimary(
                    primarySurface.baseColor,
                    primeSurfaceOpacity(primarySurface),
                    primeSurfaceOutwardShadingNormal(primarySurface),
                    primarySurface.materialFlags,
                    primarySurface.labPbrNormal,
                    primarySurface.labPbrSpecular,
                    viewDirection,
                    primeSobolSample3D(
                            preparedSample,
                            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                            PRIME_SAMPLE_DIMENSION_PRIMARY),
                    primarySurface.t,
                    volumeStack);
    primeTraceDeterministicTransmissionGuide(
            primarySurface,
            primarySample.paths.transmission,
            maximumBounces,
            result);
    primeTracePlanarReflectionGuide(
            primarySurface,
            primarySample.paths.reflection,
            volumeStack,
            result);
    // Reconstruction probes are deliberately outside the transport diagnostic domain.
    primeRawNumericalFlags = savedFlags;
    primeRawNumericalContext = savedContext;
    primeRawNumericalFirstContext = savedFirstContext;
    return result;
}

#endif
