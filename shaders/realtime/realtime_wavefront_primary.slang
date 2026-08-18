#include "realtime/realtime_wavefront_common.slang"

struct PrimeWavefrontContinuations {
    uint firstPathIndex;
    uint secondPathIndex;
    bool firstActive;
    bool secondActive;
};

PrimeWavefrontContinuations primeInitializeTransparentWavefront(
        inout PrimeIntegratorContext context,
        uint2 pixel,
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        PrimeIntegrationResult initialResult,
        uint maximumBounces) {
    PrimeWavefrontContinuations queued;
    queued.firstPathIndex = primeWavefrontIndex(pixel, 0u);
    queued.secondPathIndex = primeWavefrontIndex(pixel, 1u);
    queued.firstActive = false;
    queued.secondActive = false;
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        PrimeDenoiserState emptyState = primeInitialDenoiserState();
        PrimeWavefrontPathRecord emptyRecord = primeMakeWavefrontRecord(context.diagnostics,
                path,
                volumeStack,
                emptyState,
                initialResult.guides,
                false);
        primeWavefrontPaths[primeWavefrontIndex(pixel, 0u)] = emptyRecord;
        primeWavefrontPaths[primeWavefrontIndex(pixel, 1u)] = emptyRecord;
        primeStoreWavefrontArea(pixel, initialResult.guides);
        primeStoreWavefrontIntermediate(pixel, initialResult);
        return queued;
    }

    float3 viewDirection = -path.rayDirection;
    initialResult.reflectionSpecularRadiance = initialResult.radiance.specular;
    initialResult.radiance.specular = float3(0.0);
    primeAccumulate(context,
            initialResult.reflectionSpecularRadiance,
            primeEvaluateHitEmission(context, path, surface, false));
    float surfaceLinearRoughness = primeSurfaceLinearRoughness(surface);
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    bool discreteOnlyInterface = primeClosureIsDiscreteOnly(closureTraits);
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(context.diagnostics,
            PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
    PrimeNumericalObservation observation = primeBsdfObservationCreate();
    PrimeTransmissivePrimarySample primarySample =
            primeSampleMinecraftTransmissionPrimary(
            surface.baseColor,
            primeSurfaceOpacity(surface),
            primeSurfaceOutwardShadingNormal(surface),
            surface.materialControl,
            surface.roughness,
            surface.opticalControl,
            viewDirection,
            primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            surface.t,
            volumeStack,
            surface.adjacentBaseColor,
            surface.adjacentInterfaceControl,
            observation);
    primeCommitBsdfObservation(context, observation);
    PrimeDenoiseAlbedos albedos = primarySample.albedos;
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    initialResult.guides.primaryDistance =
            length(surface.position - primePush.cameraPosition);
    initialResult.guides.primaryPosition =
            surface.position - primePush.cameraPosition;
    initialResult.guides.primaryPreviousPosition =
            primeSurfacePreviousPosition(surface, primePush.sectionTableAddress)
                    - primePush.cameraPosition;
    initialResult.guides.primaryHasMotion = primeSurfaceHasMotion(surface);
    initialResult.guides.primaryAlbedo = albedos.diffuse;
    initialResult.guides.primaryNormal = shadingNormal;
    initialResult.guides.primaryHitKind = surface.hitKind;
    initialResult.guides.primaryMaterialFlags = primeNrdGuideControl(
            surface.materialControl, surface.opticalControl);
    initialResult.guides.primarySpecularAlbedo = albedos.specular;
    initialResult.guides.primaryLinearRoughness = surfaceLinearRoughness;
    initialResult.transparentPrimary = true;

    uint guideMode = primeTransparentGuideMode();
    bool transmissionGuideEnabled =
            guideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_NRD
            || guideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR;
    bool reflectionGuideEnabled =
            guideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_NRD
            || guideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR;
    primeStoreWavefrontVisibleScratch(pixel, initialResult.guides);
    primeStoreWavefrontArea(pixel, initialResult.guides);
    primeAppendWavefrontPath(
            PRIME_WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE,
            primeWavefrontIndex(pixel, 0u));
    PrimeTransmissiveBsdfSample branches[2] =
            PrimeTransmissiveBsdfSample[2](
                    primarySample.paths.transmission,
                    primarySample.paths.reflection);
    for (uint branch = 0u; branch < 2u; ++branch) {
        bool transmissionBranch = branch == 0u;
        bool guideEnabled = transmissionBranch
                ? transmissionGuideEnabled
                : reflectionGuideEnabled;
        PrimeTransmissiveBsdfSample selected = branches[branch];
        BsdfSample bsdf = selected.bsdfSample;
        PrimeTransparentBranchResult branchResult;
        branchResult.diffuseRadiance = transmissionBranch
                ? initialResult.radiance.diffuse
                : initialResult.reflectionDiffuseRadiance;
        branchResult.specularRadiance = transmissionBranch
                ? initialResult.radiance.specular
                : initialResult.reflectionSpecularRadiance;
        // Until a later solid-angle surface replaces the branch guide, the visible interface
        // itself is the exact fallback. This removes the need for a third guide path record.
        branchResult.guides = initialResult.guides;
        branchResult.firstHitDistance = 0.0;
        branchResult.anchorDistance = -1.0;
        branchResult.directionalGuide = false;
        PrimeQueuedPsrState psrState = primeEmptyQueuedPsrState();
        bool hasScatter = primeHasScatter(bsdf);
        bool hasGuide = guideEnabled
                && hasScatter
                && (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) == 0u;
        bool diffusePath = transmissionBranch;
        uint guideBounce = path.bounce;

        if (hasScatter) {
            primeRecordDirection(context.diagnostics, bsdf.direction);
            primeRecordNonnegative(context.diagnostics, bsdf.response);
            primeRecordNonnegative(context.diagnostics, bsdf.pdf);
            primeRecordNonnegative(context.diagnostics, bsdf.relativeEta);
            if (transmissionBranch) {
                initialResult.guides.diffuseDirection = bsdf.direction;
                branchResult.guides.diffuseDirection = bsdf.direction;
            } else {
                initialResult.guides.specularDirection = bsdf.direction;
                branchResult.guides.specularDirection = bsdf.direction;
            }
        }
        if (hasGuide) {
            branchResult.guides = primeQueuedPsrGuide(
                    branchResult.guides,
                    surface,
                    shadingNormal,
                    surfaceLinearRoughness,
                    albedos,
                    path.throughput,
                    path.physicalOrigin,
                    psrState);
            if (transmissionBranch) {
                branchResult.anchorDistance = primeQueuedPsrAnchorDistance(
                        psrState, surface.position, surface.geometricNormal);
            }
        } else if (guideEnabled && hasScatter
                && (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u) {
            psrState = primeAppendQueuedPsrDiscrete(
                    psrState, path.physicalOrigin, surface, bsdf);
        }
        if (transmissionBranch) {
            branchResult.guides.primaryAreaDiffuse =
                    initialResult.guides.primaryAreaDiffuse;
        } else {
            branchResult.guides.primaryAreaSpecular =
                    initialResult.guides.primaryAreaSpecular;
        }
        branchResult.guides.primaryAreaDirection =
                initialResult.guides.primaryAreaDirection;

        PathState branchPath = path;
        branchPath.sampleDimension = transmissionBranch ? 1u : 0u;
        bool branchDiscreteOnly = discreteOnlyInterface;
        bool continuation = hasScatter;
        if (continuation) {
            continuation = primeAdvancePath(context,
                    branchPath,
                    surface,
                    bsdf,
                    preparedSample,
                    branchDiscreteOnly,
                    surfaceLinearRoughness);
        }
        if (continuation
                && !discreteOnlyInterface
                && primeAreaLightSamplingMode()
                        != PRIME_PATH_AREA_LIGHT_SAMPLING_MODE_PURE_LIGHT_TREE) {
            branchPath.flags |= PRIME_PATH_PREVIOUS_AREA_RIS;
        }
        if (continuation) {
            branchPath.bounce = path.bounce + 1u;
            continuation = branchPath.bounce < maximumBounces;
        }
        PrimeWavefrontPathRecord record =
                primeMakeTransparentWavefrontRecord(context.diagnostics,
                branchPath,
                selected.volumeStack,
                branchResult,
                psrState,
                transmissionBranch,
                guideEnabled,
                hasGuide,
                diffusePath,
                guideBounce,
                continuation);
        uint pathIndex = primeWavefrontIndex(pixel, branch);
        primeWavefrontPaths[pathIndex] = record;
        if (transmissionBranch) {
            queued.firstActive = continuation;
        } else {
            queued.secondActive = continuation;
        }
        primeStoreTransparentBranchIntermediate(
                pixel, transmissionBranch, branchResult);
    }
    return queued;
}

void primeWavefrontPrimary(
        inout PrimeIntegratorContext context,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_SHADE_QUEUE, launchIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    uint maximumBounces = primeMaximumBounces();
    PrimeWavefrontContinuations continuations;
    continuations.firstPathIndex = recordIndex;
    continuations.secondPathIndex = primeWavefrontIndex(pixel, 1u);
    continuations.firstActive = false;
    continuations.secondActive = false;

    if (surface.hitKind == PRIME_HIT_SURFACE
            && primeMaterialIsTransmissive(surface.materialControl)) {
        PrimeIntegrationResult initialResult =
                primeLoadWavefrontIntermediate(pixel, recordIndex, record);
        continuations =
                primeInitializeTransparentWavefront(
                        context,
                        pixel,
                        path,
                        volumeStack,
                        integrator,
                        surface,
                        initialResult,
                        maximumBounces);
    } else {
        PrimeDenoiserState denoiserState = primeInitialDenoiserState();
        PrimeIntegrationResult result =
                primeLoadWavefrontIntermediate(pixel, recordIndex, record);
        bool continuation = primeIntegrateWavefrontSurface(
                PrimeStagedSecondarySunNee(),
                PrimeStagedSecondaryAreaNee(),
                PrimeNoIndirectReuse(),
                context,
                path,
                integrator,
                volumeStack,
                denoiserState,
                result,
                surface,
                recordIndex);
        continuation = continuation && path.bounce < maximumBounces;
        PrimeWavefrontPathRecord updated = primeMakeWavefrontRecord(
                context.diagnostics,
                path,
                volumeStack,
                denoiserState,
                result.guides,
                continuation);
        primeWavefrontPaths[recordIndex] = updated;
        updated.transport = primeWavefrontWithActive(updated.transport, false);
        primeWavefrontPaths[primeWavefrontIndex(pixel, 1u)] = updated;
        primeStoreWavefrontArea(pixel, result.guides);
        primeStoreWavefrontIntermediate(pixel, result);
        continuations.firstActive = continuation;
    }
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRACE_QUEUE_0,
            continuations.firstPathIndex,
            continuations.firstActive);
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRACE_QUEUE_0,
            continuations.secondPathIndex,
            continuations.secondActive);
}
