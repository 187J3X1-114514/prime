#ifndef PRIME_TRANSPARENT_GUIDE_PROBE_SLANG
#define PRIME_TRANSPARENT_GUIDE_PROBE_SLANG

// These probes are reconstruction observations. They never feed throughput, radiance or transport
// state back into the estimator, so their deterministic choices cannot bias the rendered sample.

struct PrimeTransparentGuideProbeResult {
    PrimeDenoiserGuides transmission;
    PrimeDenoiserGuides reflection;
    float transmissionAnchorDistance;
    float4 reflectionCurrentVirtualPosition;
    float4 reflectionPreviousVirtualPosition;
};

PrimeTransparentGuideProbeResult primeEmptyTransparentGuideProbeResult() {
    PrimeTransparentGuideProbeResult result;
    result.transmission = primeEmptyDenoiserGuides();
    result.reflection = primeEmptyDenoiserGuides();
    result.transmissionAnchorDistance = -1.0;
    // Negative w marks an unavailable reflection probe. Zero denotes a finite point and one a
    // direction at infinity. Current geometry feeds NRD; previous geometry feeds RR motion.
    result.reflectionCurrentVirtualPosition = float4(0.0, 0.0, 0.0, -1.0);
    result.reflectionPreviousVirtualPosition = float4(0.0, 0.0, 0.0, -1.0);
    return result;
}

float3 primeMirrorPointAcrossPlane(
        float3 point, float3 planePoint, float3 unitNormal) {
    return point - 2.0 * unitNormal * dot(point - planePoint, unitNormal);
}

float3 primeMirrorDirectionAcrossPlane(float3 direction, float3 unitNormal) {
    return direction - 2.0 * unitNormal * dot(direction, unitNormal);
}

PrimeDenoiseAlbedos primeProbeSurfaceAlbedos(
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack volumeStack) {
    if (primeMaterialIsFoliage(surface.materialControl)) {
        PrimeCompactFoliageState state = primeMinecraftFoliageState(
                surface.baseColor,
                primeSurfaceShadingNormal(surface, viewDirection),
                surface.roughness,
                surface.opticalControl,
                surface.materialControl,
                viewDirection,
                surface.t,
                volumeStack);
        return primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_FOLIAGE);
    }
    if (primeMaterialIsTransmissive(surface.materialControl)) {
        PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
                surface.baseColor,
                primeSurfaceOpacity(surface),
                primeSurfaceOutwardShadingNormal(surface),
                surface.materialControl,
                surface.roughness,
                surface.opticalControl,
                viewDirection,
                surface.t,
                volumeStack,
                surface.adjacentBaseColor,
                surface.adjacentInterfaceControl);
        return primeDenoiseAlbedosFromState(
                state, viewDirection, PRIME_DENOISE_CLOSURE_TRANSMISSIVE);
    }
    PrimeCompactOpaqueState state = primeOpaqueState(
            surface.baseColor,
            primeSurfaceShadingNormal(surface, viewDirection),
            surface.roughness,
            surface.opticalControl,
            surface.materialControl,
            viewDirection,
            surface.t,
            volumeStack);
    return primeDenoiseAlbedosFromState(
            state, viewDirection, PRIME_DENOISE_CLOSURE_OPAQUE);
}

bool primeAdvanceProbeThroughput(
        inout float3 throughput, BsdfSample sampleValue) {
    if (!primeHasScatter(sampleValue)) {
        return false;
    }
    throughput = primeProductOver(
            throughput, sampleValue.response, sampleValue.pdf);
    return primeNrdIsFinite(throughput)
            && any(throughput > float3(0.0));
}

bool primeAdvanceProbeMedium(
        inout float3 throughput,
        SurfaceInteraction surface,
        PrimeOpenPbrVolumeStack volumeStack) {
    if (volumeStack.count > 0u) {
        throughput *= primeSegmentTransmittance(
                volumeStack.values[volumeStack.count - 1u].extinction,
                surface.t);
    }
    return primeNrdIsFinite(throughput)
            && any(throughput > float3(0.0));
}

PrimeDenoiserGuides primeProbeSurfaceGuide(
        PrimeDenoiserGuides guides,
        SurfaceInteraction surface,
        float3 virtualPosition,
        float3 previousVirtualPosition,
        float3 virtualNormal,
        float3 throughput,
        PrimeDenoiseAlbedos albedos,
        bool hasMotion) {
    guides.primaryDistance = length(virtualPosition);
    guides.primaryPosition = virtualPosition;
    guides.primaryPreviousPosition = previousVirtualPosition;
    guides.primaryHasMotion = hasMotion;
    guides.primaryAlbedo = primeSanitizeDenoiseAlbedo(
            throughput * albedos.diffuse);
    guides.primaryNormal = primeNrdSafeNormalize(
            virtualNormal, float3(0.0, 1.0, 0.0));
    guides.primaryHitKind = surface.hitKind;
    guides.primaryMaterialFlags = primeNrdGuideControl(
            surface.materialControl, surface.opticalControl);
    guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
            throughput * albedos.specular);
    guides.primaryLinearRoughness = primeSurfaceLinearRoughness(surface);
    return guides;
}

PrimeDenoiserGuides primeGuideWithProbeSurface(
        PrimeDenoiserGuides destination,
        PrimeDenoiserGuides source) {
    if (source.primaryHitKind != PRIME_HIT_SURFACE) {
        return destination;
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
    return destination;
}

void primeTraceDeterministicTransmissionGuide(
        inout PrimeIntegratorContext context,
        SurfaceInteraction primarySurface,
        PrimeTransmissiveBsdfSample initialSample,
        float3 initialThroughput,
        uint maximumBounces,
        inout PrimeTransparentGuideProbeResult result) {
    BsdfSample initialBsdf = initialSample.bsdfSample;
    float3 throughput = initialThroughput;
    if ((initialBsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) == 0u
            || !primeAdvanceProbeThroughput(throughput, initialBsdf)) {
        return;
    }

    PrimeQueuedPsrState currentState = primeEmptyQueuedPsrState();
    PrimeQueuedPsrState previousState = primeEmptyQueuedPsrState();
    PrimePreviousDynamicSurface primaryPrevious = primeSurfacePrevious(
            primarySurface, primePush.sectionTableAddress);
    float3 primaryPreviousPosition = primaryPrevious.position;
    bool initialReflection =
            (initialBsdf.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u;
    currentState = primeAppendQueuedPsrState(
            currentState,
            primePush.cameraPosition,
            primePush.cameraPosition,
            primarySurface.position,
            primarySurface.geometricNormal,
            initialReflection);
    previousState = primeAppendQueuedPsrState(
            previousState,
            primePush.cameraPosition,
            primePush.cameraPosition,
            primaryPreviousPosition,
            primaryPrevious.geometricNormal,
            initialReflection);

    float3 lastPosition = primarySurface.position;
    float3 lastPreviousPosition = primaryPreviousPosition;
    float3 direction = initialBsdf.direction;
    float3 traceOrigin = primeOffsetRayOrigin(
            primarySurface.position,
            primarySurface.geometricNormal,
            direction);
    PrimeOpenPbrVolumeStack volumeStack = initialSample.volumeStack;
    bool hasMotion = primeSurfaceHasMotion(primarySurface);

    [loop]
    for (uint bounce = 1u; bounce < maximumBounces; ++bounce) {
        SurfaceInteraction surface = primeTraceSurfaceWithoutReorder(
                traceOrigin, direction);
        if (!primeKnownHitKind(surface) || surface.hitKind == PRIME_HIT_NONE) {
            return;
        }
        // The reconstruction probe observes the same filtered target as the radiance path.
        // Omitting segment absorption turns medium color into apparent illumination and lets
        // denoisers exchange complementary hues across water and stained-glass boundaries.
        if (!primeAdvanceProbeMedium(throughput, surface, volumeStack)) {
            return;
        }
        float3 viewDirection = -direction;
        PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
                surface, viewDirection, volumeStack);
        bool discreteOnlyInterface = primeClosureIsDiscreteOnly(closureTraits);
        if (!discreteOnlyInterface) {
            float3 currentVirtualPosition;
            float3 currentVirtualNormal;
            if (!primeBuildQueuedPsrGuideValue(
                    currentState,
                    lastPosition,
                    surface.position,
                    primeSurfaceShadingNormal(surface, viewDirection),
                    currentVirtualPosition,
                    currentVirtualNormal)) {
                return;
            }

            PrimePreviousDynamicSurface previousSurface = primeSurfacePrevious(
                    surface, primePush.sectionTableAddress);
            float3 surfacePreviousPosition = previousSurface.position;
            float3 previousVirtualPosition;
            float3 ignoredPreviousNormal;
            bool previousValid = primeBuildQueuedPsrGuideValue(
                    previousState,
                    lastPreviousPosition,
                    surfacePreviousPosition,
                    previousSurface.geometricNormal,
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
                            previousSurface.geometricNormal)
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
            result.transmission = primeProbeSurfaceGuide(
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
                        surface.materialControl,
                        surface.roughness,
                        surface.opticalControl,
                        viewDirection,
                        surface.t,
                        volumeStack,
                        surface.adjacentBaseColor,
                        surface.adjacentInterfaceControl);
        BsdfSample bsdf = continuation.bsdfSample;
        if ((bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) == 0u
                || !primeAdvanceProbeThroughput(throughput, bsdf)) {
            return;
        }
        PrimePreviousDynamicSurface previousSurface = primeSurfacePrevious(
                surface, primePush.sectionTableAddress);
        float3 surfacePreviousPosition = previousSurface.position;
        bool reflection =
                (bsdf.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u;
        currentState = primeAppendQueuedPsrState(
                currentState,
                primePush.cameraPosition,
                lastPosition,
                surface.position,
                surface.geometricNormal,
                reflection);
        previousState = primeAppendQueuedPsrState(
                previousState,
                primePush.cameraPosition,
                lastPreviousPosition,
                surfacePreviousPosition,
                previousSurface.geometricNormal,
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
        inout PrimeIntegratorContext context,
        SurfaceInteraction primarySurface,
        PrimeTransmissiveBsdfSample initialSample,
        PrimeOpenPbrVolumeStack initialVolumeStack,
        float3 initialThroughput,
        inout PrimeTransparentGuideProbeResult result) {
    BsdfSample bsdf = initialSample.bsdfSample;
    float3 throughput = initialThroughput;
    if ((bsdf.eventFlags
            & (PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_DISCRETE))
                    != (PRIME_BSDF_EVENT_REFLECTION | PRIME_BSDF_EVENT_DISCRETE)
            || !primeAdvanceProbeThroughput(throughput, bsdf)) {
        return;
    }

    float3 planeNormal = primeNrdSafeNormalize(
            primeSurfaceOutwardShadingNormal(primarySurface),
            primarySurface.geometricNormal);
    float3 traceOrigin = primeOffsetRayOrigin(
            primarySurface.position,
            primarySurface.geometricNormal,
            bsdf.direction);
    SurfaceInteraction target = primeTraceSurfaceWithoutReorder(
            traceOrigin, bsdf.direction);
    if (!primeKnownHitKind(target)) {
        return;
    }
    if (target.hitKind == PRIME_HIT_NONE) {
        float3 virtualDirection = primeNrdSafeNormalize(
                primeMirrorDirectionAcrossPlane(bsdf.direction, planeNormal),
                -bsdf.direction);
        PrimePreviousDynamicSurface primaryPrevious = primeSurfacePrevious(
                primarySurface, primePush.sectionTableAddress);
        float3 previousPlaneNormal = primeNrdSafeNormalize(
                primaryPrevious.geometricNormal,
                planeNormal);
        float3 previousVirtualDirection = primeSurfaceHasMotion(primarySurface)
                ? primeNrdSafeNormalize(
                        primeMirrorDirectionAcrossPlane(
                                bsdf.direction, previousPlaneNormal),
                        virtualDirection)
                : virtualDirection;
        result.reflectionCurrentVirtualPosition =
                float4(virtualDirection, 1.0);
        result.reflectionPreviousVirtualPosition =
                float4(previousVirtualDirection, 1.0);
        return;
    }
    if (!primeAdvanceProbeMedium(throughput, target, initialVolumeStack)) {
        return;
    }

    PrimePreviousDynamicSurface primaryPrevious = primeSurfacePrevious(
            primarySurface, primePush.sectionTableAddress);
    float3 primaryPreviousPosition = primaryPrevious.position;
    float3 previousPlaneNormal = primeNrdSafeNormalize(
            primaryPrevious.geometricNormal,
            planeNormal);
    PrimePreviousDynamicSurface targetPrevious = primeSurfacePrevious(
            target, primePush.sectionTableAddress);
    float3 targetPreviousPosition = targetPrevious.position;
    float3 currentVirtualPosition = primeMirrorPointAcrossPlane(
            target.position, primarySurface.position, planeNormal)
            - primePush.cameraPosition;
    float3 previousVirtualPosition = primeMirrorPointAcrossPlane(
            targetPreviousPosition, primaryPreviousPosition, previousPlaneNormal)
            - primePush.cameraPosition;
    // Traversal starts from a normal-shifted origin, but the virtual target must remain on the
    // physical camera ray. Mirroring and removing that offset prevents a static smooth reflector
    // from manufacturing motion at grazing incidence while retaining the self-hit guard.
    float3 mirroredTraceOffset = primeMirrorDirectionAcrossPlane(
            traceOrigin - primarySurface.position, planeNormal);
    float3 previousMirroredTraceOffset = primeMirrorDirectionAcrossPlane(
            traceOrigin - primarySurface.position, previousPlaneNormal);
    currentVirtualPosition -= mirroredTraceOffset;
    previousVirtualPosition -= previousMirroredTraceOffset;
    float3 targetNormal = primeSurfaceShadingNormal(target, -bsdf.direction);
    float3 virtualNormal = primeMirrorDirectionAcrossPlane(
            targetNormal, planeNormal);
    PrimeDenoiseAlbedos albedos = primeProbeSurfaceAlbedos(
            target, -bsdf.direction, initialVolumeStack);
    bool hasMotion = primeSurfaceHasMotion(primarySurface)
            || primeSurfaceHasMotion(target);
    result.reflection = primeProbeSurfaceGuide(
            result.reflection,
            target,
            currentVirtualPosition,
            hasMotion ? previousVirtualPosition : currentVirtualPosition,
            virtualNormal,
            throughput,
            albedos,
            hasMotion);
    result.reflectionCurrentVirtualPosition = float4(
            currentVirtualPosition, 0.0);
    result.reflectionPreviousVirtualPosition = float4(
            hasMotion ? previousVirtualPosition : currentVirtualPosition,
            0.0);
}

PrimeTransparentGuideProbeResult primeTraceTransparentGuideProbes(
        inout PrimeIntegratorContext context,
        PathState cameraPath,
        SurfaceInteraction primarySurface,
        PrimeOpenPbrVolumeStack volumeStack,
        uint maximumBounces) {
    PrimeTransparentGuideProbeResult result =
            primeEmptyTransparentGuideProbeResult();
    if (maximumBounces <= 1u
            || primarySurface.hitKind != PRIME_HIT_SURFACE
            || !primeMaterialIsTransmissive(primarySurface.materialControl)) {
        return result;
    }

    PrimeNumericalDiagnostics savedDiagnostics = context.diagnostics;
    float3 initialThroughput = float3(1.0);
    if (!primeAdvanceProbeMedium(
            initialThroughput, primarySurface, volumeStack)) {
        context.diagnostics = savedDiagnostics;
        return result;
    }
    float3 viewDirection = -cameraPath.rayDirection;
    PrimePreparedSampleBase preparedSample = primePrepareSampleBase(
            primeMakeSampleBase(cameraPath, cameraPath.bounce + 1u));
    PrimeTransmissivePrimarySample primarySample =
            primeSampleMinecraftTransmissionPrimary(
                    primarySurface.baseColor,
                    primeSurfaceOpacity(primarySurface),
                    primeSurfaceOutwardShadingNormal(primarySurface),
                    primarySurface.materialControl,
                    primarySurface.roughness,
                    primarySurface.opticalControl,
                    viewDirection,
                    primeSobolSample3D(
                            preparedSample,
                            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                            PRIME_SAMPLE_DIMENSION_PRIMARY),
                    primarySurface.t,
                    volumeStack,
                    primarySurface.adjacentBaseColor,
                    primarySurface.adjacentInterfaceControl);
    primeTraceDeterministicTransmissionGuide(
            context,
            primarySurface,
            primarySample.paths.transmission,
            initialThroughput,
            maximumBounces,
            result);
    primeTracePlanarReflectionGuide(
            context,
            primarySurface,
            primarySample.paths.reflection,
            volumeStack,
            initialThroughput,
            result);
    // Reconstruction probes are deliberately outside the transport diagnostic domain.
    context.diagnostics = savedDiagnostics;
    return result;
}

#endif
