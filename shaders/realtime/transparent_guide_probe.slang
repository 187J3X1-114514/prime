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
    bool reflectionDirectionalGuide;
    bool reflectionHasMotion;
};

static const uint PRIME_GUIDE_PROBE_TRANSMISSION = 0u;
static const uint PRIME_GUIDE_PROBE_REFLECTION = 1u;
static const uint PRIME_GUIDE_PROBE_TRANSMISSION_MASK =
        1u << PRIME_GUIDE_PROBE_TRANSMISSION;
static const uint PRIME_GUIDE_PROBE_REFLECTION_MASK =
        1u << PRIME_GUIDE_PROBE_REFLECTION;

PrimeTransparentGuideProbeResult primeEmptyTransparentGuideProbeResult() {
    PrimeTransparentGuideProbeResult result;
    result.transmission = primeEmptyDenoiserGuides();
    result.reflection = primeEmptyDenoiserGuides();
    result.transmissionAnchorDistance = -1.0;
    // Negative w marks an unavailable reflection probe. Zero denotes a finite point and one a
    // direction at infinity. Current geometry feeds NRD; previous geometry feeds RR motion.
    result.reflectionCurrentVirtualPosition = float4(0.0, 0.0, 0.0, -1.0);
    result.reflectionPreviousVirtualPosition = float4(0.0, 0.0, 0.0, -1.0);
    result.reflectionDirectionalGuide = false;
    result.reflectionHasMotion = false;
    return result;
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

float3 primeGuideProbeEventNormal(
        SurfaceInteraction surface,
        float3 viewDirection,
        BsdfSample sampleValue) {
    if ((sampleValue.eventFlags & PRIME_BSDF_EVENT_REFLECTION) == 0u) {
        return surface.geometricNormal;
    }
    // The sampled directions define the actual delta plane, including a smooth shading normal.
    return primeNrdSafeNormalize(
            viewDirection + sampleValue.direction,
            primeSurfaceOutwardShadingNormal(surface));
}

struct PrimeGuideProbeContinuation {
    PrimePathScatter scatter;
    bool unique;
};

PrimeGuideProbeContinuation primeSampleGuideProbeContinuation(
        inout PrimeIntegratorContext context,
        SurfaceInteraction surface,
        float3 viewDirection,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeGuideProbeContinuation result;
    result.scatter.bsdf = primeInvalidBsdfSample();
    result.scatter.volumeStack = volumeStack;
    result.unique = false;
    if (primeMaterialIsTransmissive(surface.materialControl)) {
        // Preserve one canonical target through paired entry/exit boundaries. A deterministic
        // transmission continuation keeps the guide behind the glass; TIR is the sole reflected
        // continuation because no refracted direction exists there.
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
        result.scatter.bsdf = continuation.bsdfSample;
        result.scatter.volumeStack = continuation.volumeStack;
        result.unique = primeHasScatter(result.scatter.bsdf)
                && (result.scatter.bsdf.eventFlags
                        & PRIME_BSDF_EVENT_DISCRETE) != 0u;
        return result;
    }
    // The only opaque discrete-only production topology is a smooth conductor. Its geometric
    // continuation is unique, so a fixed sample cannot make the guide identity flicker.
    result.scatter = primeSamplePathSurface(
            context,
            surface,
            viewDirection,
            float3(0.5),
            volumeStack);
    result.unique = primeHasScatter(result.scatter.bsdf)
            && (result.scatter.bsdf.eventFlags
                    & PRIME_BSDF_EVENT_DISCRETE) != 0u;
    return result;
}

void primeStoreGuideProbeDirection(
        uint lane,
        PrimeQueuedPsrState currentState,
        PrimeQueuedPsrState previousState,
        float3 direction,
        bool hasMotion,
        inout PrimeTransparentGuideProbeResult result) {
    if (lane != PRIME_GUIDE_PROBE_REFLECTION) {
        return;
    }
    float3 currentVirtualDirection = primeNrdSafeNormalize(
            primeQueuedPsrVirtualDirection(currentState, direction),
            currentState.firstDirectionLength.xyz);
    float3 previousVirtualDirection = hasMotion
            ? primeNrdSafeNormalize(
                    primeQueuedPsrVirtualDirection(previousState, direction),
                    currentVirtualDirection)
            : currentVirtualDirection;
    result.reflectionCurrentVirtualPosition =
            float4(currentVirtualDirection, 1.0);
    result.reflectionPreviousVirtualPosition =
            float4(previousVirtualDirection, 1.0);
    result.reflectionDirectionalGuide = true;
    result.reflectionHasMotion = hasMotion;
}

void primeTraceDiscreteGuideProbe(
        inout PrimeIntegratorContext context,
        uint lane,
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

    float3 initialViewDirection = primeNrdSafeNormalize(
            primePush.cameraPosition - primarySurface.position,
            -initialBsdf.direction);
    float3 initialEventNormal = primeGuideProbeEventNormal(
            primarySurface, initialViewDirection, initialBsdf);
    PrimePreviousDynamicSurface primaryPrevious = primeSurfacePrevious(
            primarySurface, primePush.sectionTableAddress);
    float3 primaryPreviousNormal = primeSurfaceHasMotion(primarySurface)
            ? primaryPrevious.geometricNormal
            : initialEventNormal;
    PrimeQueuedPsrState currentState = primeAppendQueuedPsrState(
            primeEmptyQueuedPsrState(),
            primePush.cameraPosition,
            primePush.cameraPosition,
            primarySurface.position,
            initialEventNormal,
            (initialBsdf.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u);
    PrimeQueuedPsrState previousState = primeAppendQueuedPsrState(
            primeEmptyQueuedPsrState(),
            primePush.cameraPosition,
            primePush.cameraPosition,
            primaryPrevious.position,
            primaryPreviousNormal,
            (initialBsdf.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u);

    float3 lastPreviousPosition = primaryPrevious.position;
    float3 direction = initialBsdf.direction;
    float3 physicalOrigin = primarySurface.position;
    uint2 sourcePrimitive = uint2(
            primarySurface.sectionIndex,
            primeSurfaceTriangleIndex(primarySurface));
    PrimeOpenPbrVolumeStack volumeStack = initialSample.volumeStack;
    bool hasMotion = primeSurfaceHasMotion(primarySurface);

    [loop]
    for (uint bounce = 1u; bounce < maximumBounces; ++bounce) {
        SurfaceInteraction surface = primeTraceSurfaceWithoutReorder(
                physicalOrigin, direction, sourcePrimitive);
        if (!primeKnownHitKind(surface)) {
            return;
        }
        if (surface.hitKind == PRIME_HIT_NONE) {
            primeStoreGuideProbeDirection(
                    lane,
                    currentState,
                    previousState,
                    direction,
                    hasMotion,
                    result);
            return;
        }
        // Reconstruction observes the same medium filtering as radiance transport.
        if (!primeAdvanceProbeMedium(throughput, surface, volumeStack)) {
            return;
        }

        float3 viewDirection = -direction;
        PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
                surface, viewDirection, volumeStack);
        bool terminalSurface = !primeClosureIsDiscreteOnly(closureTraits);
        PrimeGuideProbeContinuation continuation;
        continuation.scatter.bsdf = primeInvalidBsdfSample();
        continuation.scatter.volumeStack = volumeStack;
        continuation.unique = false;
        if (!terminalSurface) {
            continuation = primeSampleGuideProbeContinuation(
                    context, surface, viewDirection, volumeStack);
            // An invalid or non-delta continuation cannot be collapsed into this guide identity.
            terminalSurface = !continuation.unique;
            if (!terminalSurface
                    && primeQueuedPsrCount(currentState)
                            >= PRIME_QUEUED_PSR_CAPACITY) {
                return;
            }
        }
        if (terminalSurface) {
            float3 currentVirtualPosition;
            float3 currentVirtualNormal;
            // Exact source-primitive rejection keeps the physical segment origin unchanged, so
            // traced segment lengths remain in the same coordinate construction as the guide.
            if (!primeBuildQueuedPsrGuideValueFromSegment(
                    currentState,
                    surface.t,
                    primeSurfaceShadingNormal(surface, viewDirection),
                    currentVirtualPosition,
                    currentVirtualNormal)) {
                return;
            }

            bool terminalHasMotion = hasMotion || primeSurfaceHasMotion(surface);
            PrimePreviousDynamicSurface previousSurface = primeSurfacePrevious(
                    surface, primePush.sectionTableAddress);
            float3 previousVirtualPosition = currentVirtualPosition;
            bool previousValid = true;
            if (terminalHasMotion) {
                float3 ignoredPreviousNormal;
                previousValid = primeBuildQueuedPsrGuideValue(
                        previousState,
                        lastPreviousPosition,
                        previousSurface.position,
                        previousSurface.geometricNormal,
                        previousVirtualPosition,
                        ignoredPreviousNormal);
            }

            float anchorDistance = -1.0;
            if (lane == PRIME_GUIDE_PROBE_TRANSMISSION) {
                anchorDistance = primeQueuedPsrAnchorDistanceValue(
                        currentState,
                        primePush.cameraPosition,
                        surface.position,
                        surface.geometricNormal);
                if (anchorDistance > 0.0) {
                    currentVirtualPosition =
                            currentState.firstDirectionLength.xyz
                                    * anchorDistance;
                    float previousAnchor = previousValid && terminalHasMotion
                            ? primeQueuedPsrAnchorDistanceValue(
                                    previousState,
                                    primePush.cameraPosition,
                                    previousSurface.position,
                                    previousSurface.geometricNormal)
                            : anchorDistance;
                    if (previousAnchor > 0.0) {
                        previousVirtualPosition =
                                terminalHasMotion
                                ? previousState.firstDirectionLength.xyz
                                        * previousAnchor
                                : currentVirtualPosition;
                    } else {
                        previousValid = false;
                    }
                }
            }
            if (!previousValid) {
                previousVirtualPosition = currentVirtualPosition;
            }

            PrimeDenoiseAlbedos albedos = primeProbeSurfaceAlbedos(
                    surface, viewDirection, volumeStack);
            PrimeDenoiserGuides guide = primeProbeSurfaceGuide(
                    primeEmptyDenoiserGuides(),
                    surface,
                    currentVirtualPosition,
                    previousVirtualPosition,
                    currentVirtualNormal,
                    throughput,
                    albedos,
                    terminalHasMotion && previousValid);
            if (lane == PRIME_GUIDE_PROBE_TRANSMISSION) {
                result.transmission = guide;
                result.transmissionAnchorDistance = anchorDistance;
            } else {
                result.reflection = guide;
                result.reflectionCurrentVirtualPosition =
                        float4(currentVirtualPosition, 0.0);
                result.reflectionPreviousVirtualPosition =
                        float4(previousVirtualPosition, 0.0);
                result.reflectionDirectionalGuide = false;
                result.reflectionHasMotion =
                        terminalHasMotion && previousValid;
            }
            return;
        }

        BsdfSample bsdf = continuation.scatter.bsdf;
        if ((bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) == 0u
                || !primeAdvanceProbeThroughput(throughput, bsdf)) {
            return;
        }

        PrimePreviousDynamicSurface previousSurface = primeSurfacePrevious(
                surface, primePush.sectionTableAddress);
        float3 eventNormal = primeGuideProbeEventNormal(
                surface, viewDirection, bsdf);
        float3 previousEventNormal = primeSurfaceHasMotion(surface)
                ? previousSurface.geometricNormal
                : eventNormal;
        bool reflection =
                (bsdf.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u;
        currentState = primeAppendQueuedPsrSegment(
                currentState,
                primePush.cameraPosition,
                surface.position,
                surface.t,
                eventNormal,
                reflection);
        previousState = primeAppendQueuedPsrState(
                previousState,
                primePush.cameraPosition,
                lastPreviousPosition,
                previousSurface.position,
                previousEventNormal,
                reflection);
        lastPreviousPosition = previousSurface.position;
        hasMotion = hasMotion || primeSurfaceHasMotion(surface);
        direction = bsdf.direction;
        physicalOrigin = surface.position;
        sourcePrimitive = uint2(
                surface.sectionIndex, primeSurfaceTriangleIndex(surface));
        volumeStack = continuation.scatter.volumeStack;
    }
}

PrimeTransparentGuideProbeResult primeTraceTransparentGuideProbes(
        inout PrimeIntegratorContext context,
        PathState cameraPath,
        SurfaceInteraction primarySurface,
        PrimeOpenPbrVolumeStack volumeStack,
        uint maximumBounces,
        uint probeMask) {
    PrimeTransparentGuideProbeResult result =
            primeEmptyTransparentGuideProbeResult();
    if (primarySurface.hitKind != PRIME_HIT_SURFACE
            || !primeMaterialIsTransmissive(primarySurface.materialControl)) {
        return result;
    }

    float3 initialThroughput = float3(1.0);
    if (!primeAdvanceProbeMedium(
            initialThroughput, primarySurface, volumeStack)) {
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
    if ((probeMask & PRIME_GUIDE_PROBE_TRANSMISSION_MASK) != 0u) {
        primeTraceDiscreteGuideProbe(
                context,
                PRIME_GUIDE_PROBE_TRANSMISSION,
                primarySurface,
                primarySample.paths.transmission,
                initialThroughput,
                maximumBounces,
                result);
    }
    if ((probeMask & PRIME_GUIDE_PROBE_REFLECTION_MASK) != 0u) {
        primeTraceDiscreteGuideProbe(
                context,
                PRIME_GUIDE_PROBE_REFLECTION,
                primarySurface,
                primarySample.paths.reflection,
                initialThroughput,
                maximumBounces,
                result);
    }
    return result;
}

#endif
