import "prime_realtime_abi.slang";
import "core/atmosphere_rt.slang";
import "core/numerical_diagnostics.slang";
import "core/nrd_common.slang";
import "core/packed_geometry.slang";
import "core/render_config.slang";
import "rt/shadow_payload.slang";

import "core/sampling.slang";
import "core/transparent_checkerboard.slang";
#include "bsdf/adapter/bsdf.slang"
import "lighting/lights.slang";

#include "integrator/integrator.slang"
import "core/camera.slang";
#include "realtime/transparent_guide_probe.slang"
#include "realtime/wavefront_output.slang"
#include "realtime/wavefront_state.slang"

#if defined(PRIME_ENABLE_SUBGROUP_QUEUE)
typealias PrimeWavefrontContinuationAppender =
        PrimeSubgroupWavefrontContinuationAppender;
#else
typealias PrimeWavefrontContinuationAppender =
        PrimeScalarWavefrontContinuationAppender;
#endif

struct PrimeWavefrontShaderRecord {
    uint control;
};

[[vk::shader_record]]
ConstantBuffer<PrimeWavefrontShaderRecord> primeWavefrontShaderRecord;

struct PrimeWavefrontContinuation {
    uint pathIndex;
    bool active;
};

bool primeSecondarySamplesAreaLight(PrimePreparedSampleBase preparedSample) {
    return primeSobolSample1D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_DIRECT_LIGHT_FAMILY,
            PRIME_SAMPLE_DIMENSION_PRIMARY)
            < PRIME_SECONDARY_LIGHT_FAMILY_PROBABILITY;
}

struct PrimeDeferredSecondarySunNee : IPrimeSecondarySunNee {
    float3 estimate(
            inout PrimeIntegratorContext context,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeRcVolumeStack volumeStack,
            float3 pathThroughput,
            uint pathIndex) {
        if (primeSecondarySamplesAreaLight(preparedSample)) {
            return float3(0.0);
        }
        PrimePreparedSunShadow prepared = primePrepareDirectSunShadow(
                context,
                integrator,
                surface,
                viewDirection,
                primeSobolSample2D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                        PRIME_SAMPLE_DIMENSION_PRIMARY),
                volumeStack,
                PRIME_SECONDARY_LIGHT_FAMILY_PROBABILITY);
        float3 contribution = pathThroughput * prepared.contribution;
        if (prepared.active && any(contribution > float3(0.0))) {
            uint control = asuint(primeWavefrontPaths[pathIndex]
                    .transport.rayDirectionAndDenoiserControl.w);
            primeStoreWavefrontSunShadowWork(
                    pathIndex,
                    prepared,
                    surface,
                    volumeStack,
                    contribution,
                    control);
            primeAppendWavefrontPath(PRIME_WAVEFRONT_SUN_QUEUE, pathIndex);
        }
        return float3(0.0);
    }
};

PrimeWavefrontContinuation primeInitializeTransparentWavefront(
        inout PrimeIntegratorContext context,
        uint2 pixel,
        PathState path,
        PrimeRcVolumeStack volumeStack,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        PrimeIntegrationResult initialResult,
        uint maximumBounces) {
    PrimeWavefrontContinuation queued;
    queued.pathIndex = primeWavefrontIndex(pixel, 0u);
    queued.active = false;
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        PrimeDenoiserState emptyState = primeInitialDenoiserState();
        PrimeWavefrontPathRecord emptyRecord = primeMakeWavefrontRecord(context.diagnostics,
                path,
                volumeStack,
                emptyState,
                initialResult.guides.primaryMaterialFlags,
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
            primeSurfacePreviousPosition(surface) - primePush.cameraPosition;
    initialResult.guides.primaryHasMotion = primeSurfaceHasMotion(surface);
    initialResult.guides.primaryAlbedo = albedos.diffuse;
    initialResult.guides.primaryNormal = shadingNormal;
    initialResult.guides.primaryHitKind = surface.hitKind;
    initialResult.guides.primaryMaterialFlags = surface.materialControl;
    initialResult.guides.primarySpecularAlbedo = albedos.specular;
    initialResult.guides.primaryLinearRoughness = surfaceLinearRoughness;
    initialResult.transparentPrimary = true;

    uint guideMode = primeTransparentGuideMode();
    bool transmissionGuideEnabled =
            guideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_NRD
            || guideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR;
    bool reflectionGuideEnabled =
            guideMode == PRIME_PATH_TRANSPARENT_GUIDE_MODE_NRD;
    primeStoreWavefrontVisibleScratch(pixel, initialResult.guides);
    primeStoreWavefrontArea(pixel, initialResult.guides);
    primeAppendWavefrontPath(
            PRIME_WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE,
            primeWavefrontIndex(pixel, 0u));
    PrimeTransmissiveBsdfSample branches[2] =
            PrimeTransmissiveBsdfSample[2](
                    primarySample.paths.transmission,
                    primarySample.paths.reflection);
    bool sampleReflection = primeTransparentCheckerboardSamplesReflection(
            pixel, path.sampleIndex);

    for (uint branch = 0u; branch < 2u; ++branch) {
        bool transmissionBranch = branch == 0u;
        bool selectedBranch = transmissionBranch != sampleReflection;
        bool guideEnabled = transmissionBranch
                ? transmissionGuideEnabled
                : reflectionGuideEnabled;
        PrimeTransmissiveBsdfSample selected = branches[branch];
        BsdfSample bsdf = selectedBranch
                ? selected.bsdfSample
                : primeInvalidBsdfSample();
        if (selectedBranch) {
            bsdf.pdf = primeTransparentCheckerboardPdf(bsdf.pdf);
        } else {
            selected.volumeStack = volumeStack;
        }
        PrimeTransparentBranchResult branchResult;
        branchResult.diffuseRadiance = transmissionBranch
                ? initialResult.radiance.diffuse
                : initialResult.reflectionDiffuseRadiance;
        branchResult.specularRadiance = transmissionBranch
                ? initialResult.radiance.specular
                : initialResult.reflectionSpecularRadiance;
        // Until a later solid-angle surface replaces the branch guide, the visible interface
        // itself is the exact fallback. This removes the need for a third 96-byte guide record.
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
        branchPath.sampleDimension = 0u;
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
        if (continuation) {
            queued.pathIndex = pathIndex;
            queued.active = true;
        }
        primeStoreTransparentBranchIntermediate(
                pixel, transmissionBranch, branchResult);
    }
    return queued;
}

void primeWavefrontHead(
        inout PrimeIntegratorContext context, uint2 launchIndex) {
    uint2 pixel = launchIndex;
    primeSetNumericalContext(context.diagnostics, PRIME_NUMERICAL_STAGE_CAMERA, 0u);
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    uint maximumBounces = primeMaximumBounces();
    bool continuation = maximumBounces != 0u;
    uint pathIndex = primeWavefrontIndex(pixel, 0u);
    if (continuation) {
        float3 traceDirection = primeCameraRayDirection(pixel, cameraSample);
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_TRACE, 0u);
        SurfaceInteraction surface = primeTraceSurfaceClassified(
                PrimeSurfaceTracePolicy(),
                primePush.cameraPosition,
                traceDirection,
                0u);
        primeStoreWavefrontSurface(pathIndex, surface);
    }

    // Build large transport records after SER so they do not enter the reorder spill set.
    PathState path = primeCameraPath(pixel, 0u, cameraSample);
    PrimeRcVolumeStack volumeStack =
            (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
    PrimeDenoiserState denoiserState = primeInitialDenoiserState();
    PrimeIntegrationResult result = primeEmptyIntegrationResult();
    PrimeWavefrontPathRecord record = primeMakeWavefrontRecord(
            context.diagnostics,
            path,
            volumeStack,
            denoiserState,
            result.guides.primaryMaterialFlags,
            continuation);
    primeWavefrontPaths[pathIndex] = record;
    record.transport = primeWavefrontWithActive(record.transport, false);
    primeWavefrontPaths[primeWavefrontIndex(pixel, 1u)] = record;
    primeStoreWavefrontArea(pixel, result.guides);
    primeStoreWavefrontIntermediate(pixel, result);
    // SER may repack invocations. Queue publication after it must not use subgroup collectives.
    if (continuation) {
        primeAppendWavefrontPath(PRIME_WAVEFRONT_SHADE_QUEUE, pathIndex);
        if (primeLoadWavefrontSurface(pathIndex).hitKind == PRIME_HIT_SURFACE) {
            primeAppendWavefrontPath(PRIME_WAVEFRONT_AREA_QUEUE, pathIndex);
        }
    }
}

void primeWavefrontPrimaryArea(
        inout PrimeIntegratorContext context,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeRcVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return;
    }
    float3 viewDirection = -path.rayDirection;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    if (!primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
        return;
    }

    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    PrimeDirectLightingSplit direct = primeEstimatePrimaryNonsunDirect(
            context,
            surface,
            viewDirection,
            preparedSample,
            volumeStack,
            primeMaterialIsTransmissive(surface.materialControl));
    direct.diffuse *= path.throughput;
    direct.specular *= path.throughput;
    primeAccumulatePrimaryDirectSplit(context, pixel, direct);

    PrimeDenoiserGuides guides = primeEmptyDenoiserGuides();
    guides.primaryAreaDiffuse = direct.diffuse;
    guides.primaryAreaSpecular = direct.specular;
    if (any(direct.diffuse > float3(0.0))
            || any(direct.specular > float3(0.0))) {
        guides.primaryAreaDirection = direct.direction;
    }
    primeStoreWavefrontArea(pixel, guides);
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
}

void primeWavefrontPrimarySun(
        inout PrimeIntegratorContext context,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeRcVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return;
    }
    float3 viewDirection = -path.rayDirection;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    if (!primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
        return;
    }

    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    bool transparent = primeMaterialIsTransmissive(surface.materialControl);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
            context,
            integrator,
            surface,
            viewDirection,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            volumeStack,
            transparent);
    PrimeDirectLightingSplit direct = sun.lighting;
    direct.diffuse *= path.throughput;
    direct.specular *= path.throughput;
    if (transparent) {
        direct.diffuse *= sun.visibility;
        direct.specular *= sun.visibility;
        primeAccumulatePrimaryDirectSplit(context, pixel, direct);
    } else {
        primeStoreWavefrontPrimarySun(
                pixel,
                direct.diffuse + direct.specular,
                sun.visibility,
                sun.penumbra);
    }
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
}

void primeWavefrontStep(
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue) {
    uint recordIndex =
            primeWavefrontQueuedPath(sourceQueue, launchIndex);
    uint packedDiagnostic = asuint(
            primeWavefrontPaths[recordIndex]
                    .transport.throughputAndNumericalFlags.w);
    context.diagnostics.flags =
            packedDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
    context.diagnostics.firstContext =
            (packedDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                    & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
    uint bounce = asuint(
            primeWavefrontPaths[recordIndex]
                    .transport.traceOriginAndPathControl.w)
            & PRIME_WAVEFRONT_BYTE_MASK;
    float3 traceOrigin = primeWavefrontPaths[recordIndex]
            .transport.traceOriginAndPathControl.xyz;
    float3 rayDirection = primeWavefrontPaths[recordIndex]
            .transport.rayDirectionAndDenoiserControl.xyz;
    uint traceControl = asuint(
            primeWavefrontPaths[recordIndex]
                    .transport.rayDirectionAndDenoiserControl.w);
    uint pathClass = (traceControl & PRIME_WAVEFRONT_TRANSPARENT_BRANCH) == 0u
            ? 0u
            : (traceControl & PRIME_WAVEFRONT_TRANSMISSION_BRANCH) == 0u
                    ? 1u
                    : 2u;
    primeSetNumericalContext(context.diagnostics, PRIME_NUMERICAL_STAGE_TRACE, bounce);
    SurfaceInteraction surface = primeTraceSurfaceClassified(
            PrimeSurfaceTracePolicy(),
            traceOrigin, rayDirection, pathClass);
    primeStoreWavefrontSurface(recordIndex, surface);
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
    // Trace may cross a SER invocation-repack boundary. Keep queue publication scalar until
    // compaction moves to a separate dispatch with a well-defined subgroup population.
    uint shadeQueue = primeWavefrontTransparentBranch(traceControl)
            ? PRIME_WAVEFRONT_TRANSPARENT_SHADE_QUEUE
            : PRIME_WAVEFRONT_SHADE_QUEUE;
    primeAppendWavefrontPath(shadeQueue, recordIndex);
    if (surface.hitKind == PRIME_HIT_SURFACE) {
        primeAppendWavefrontPath(PRIME_WAVEFRONT_AREA_QUEUE, recordIndex);
    }
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
    PrimeRcVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    uint maximumBounces = primeMaximumBounces();
    bool queueContinuation;

    if (surface.hitKind == PRIME_HIT_SURFACE
            && primeMaterialIsTransmissive(surface.materialControl)) {
        PrimeIntegrationResult initialResult =
                primeLoadWavefrontIntermediate(pixel, record);
        PrimeWavefrontContinuation transparent =
                primeInitializeTransparentWavefront(
                        context,
                        pixel,
                        path,
                        volumeStack,
                        integrator,
                        surface,
                        initialResult,
                        maximumBounces);
        recordIndex = transparent.pathIndex;
        queueContinuation = transparent.active;
    } else {
        PrimeDenoiserState denoiserState = primeInitialDenoiserState();
        PrimeIntegrationResult result =
                primeLoadWavefrontIntermediate(pixel, record);
        bool continuation = primeIntegrateWavefrontSurface(
                PrimeDeferredSecondarySunNee(),
                PrimeDeferredSecondaryAreaNee(),
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
                result.guides.primaryMaterialFlags,
                continuation);
        primeWavefrontPaths[recordIndex] = updated;
        updated.transport = primeWavefrontWithActive(updated.transport, false);
        primeWavefrontPaths[primeWavefrontIndex(pixel, 1u)] = updated;
        primeStoreWavefrontArea(pixel, result.guides);
        primeStoreWavefrontIntermediate(pixel, result);
        queueContinuation = continuation;
    }
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRACE_QUEUE_0,
            recordIndex,
            queueContinuation);
}

void primeWavefrontShade<IndirectReuse>(
        IndirectReuse indirectReuse,
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue)
    where IndirectReuse : IPrimeIndirectReuse
{
    uint recordIndex =
            primeWavefrontQueuedPath(
                    PRIME_WAVEFRONT_SHADE_QUEUE, launchIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeRcVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    uint maximumBounces = primeMaximumBounces();
    bool queueContinuation;

    if (primeSurfaceEmitterIndex(surface) != PRIME_NO_LIGHT_INDEX
            && !primePreviousCannotUseMis(path)) {
        path.previousLightNormal =
                primeLoadWavefrontPreviousLightNormal(recordIndex);
    }

    bool guideWasPending =
            (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u;
    PrimeIntegrationResult result =
            primeLoadWavefrontActiveIntermediate(pixel, control);
    PrimeDenoiserState denoiserState =
            primeWavefrontDenoiserState(control, result);
    bool continuation = primeIntegrateWavefrontSurface(
            PrimeDeferredSecondarySunNee(),
            PrimeDeferredSecondaryAreaNee(),
            indirectReuse,
            context,
            path,
            integrator,
            volumeStack,
            denoiserState,
            result,
            surface,
            recordIndex);
    continuation = continuation && path.bounce < maximumBounces;
    PrimeWavefrontTransportRecord updatedRecord =
            primeMakeUpdatedWavefrontRecord(context.diagnostics,
                    path,
                    volumeStack,
                    denoiserState,
                    control,
                    0u,
                    continuation);
    primeStoreWavefrontTransportRecord(recordIndex, updatedRecord);
    primeStoreWavefrontPreviousLightNormal(
            recordIndex, path.previousLightNormal);
    primeStoreWavefrontActiveIntermediate(
            pixel, guideWasPending, result);
    queueContinuation = continuation;
    uint targetQueue = sourceQueue == PRIME_WAVEFRONT_TRACE_QUEUE_0
            ? PRIME_WAVEFRONT_TRACE_QUEUE_1
            : PRIME_WAVEFRONT_TRACE_QUEUE_0;
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            targetQueue, recordIndex, queueContinuation);
}

void primeWavefrontTransparentShade(
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_TRANSPARENT_SHADE_QUEUE, launchIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeRcVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);

    if (primeSurfaceEmitterIndex(surface) != PRIME_NO_LIGHT_INDEX
            && !primePreviousCannotUseMis(path)) {
        path.previousLightNormal =
                primeLoadWavefrontPreviousLightNormal(recordIndex);
    }

    bool hadGuide =
            (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    bool guideEnabled =
            (control & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
    bool needsPsr = guideEnabled && !hadGuide;
    bool transmissionBranch = primeWavefrontTransmissionBranch(control);
    PrimeTransparentBranchResult branchResult =
            primeLoadTransparentBranchActiveIntermediate(
                    pixel, transmissionBranch, control);
    PrimeQueuedPsrState psrState = needsPsr
            ? primeLoadWavefrontPsrState(recordIndex, record)
            : primeEmptyQueuedPsrState();
    bool hasGuide = hadGuide;
    bool diffusePath =
            (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    uint guideBounce =
            (control >> PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT)
                    & PRIME_WAVEFRONT_BYTE_MASK;
    bool directionalGuide = primeWavefrontDirectionalGuide(control);
    bool hadDirectionalGuide = directionalGuide;
    bool continuation = primeIntegrateTransparentWavefrontSurface(
            PrimeDeferredSecondarySunNee(),
            PrimeDeferredSecondaryAreaNee(),
            context,
            path,
            integrator,
            volumeStack,
            branchResult,
            psrState,
            hasGuide,
            diffusePath,
            guideBounce,
            directionalGuide,
            surface,
            transmissionBranch,
            guideEnabled,
            recordIndex);
    continuation = continuation && path.bounce < primeMaximumBounces();
    PrimeWavefrontTransportRecord updatedRecord =
            primeMakeUpdatedTransparentWavefrontRecord(context.diagnostics,
            path,
            volumeStack,
            branchResult,
            psrState,
            hasGuide,
            diffusePath,
            guideBounce,
            directionalGuide,
            control,
            continuation);
    primeStoreWavefrontTransportRecord(recordIndex, updatedRecord);
    if (continuation && guideEnabled && !hasGuide) {
        primeStoreWavefrontPsrState(recordIndex, psrState);
    } else {
        primeStoreWavefrontPreviousLightNormal(
                recordIndex, path.previousLightNormal);
    }
    primeStoreTransparentBranchActiveIntermediate(
            pixel,
            transmissionBranch,
            !hadGuide && hasGuide,
            !hadDirectionalGuide && directionalGuide,
            branchResult);

    uint targetQueue = sourceQueue == PRIME_WAVEFRONT_TRACE_QUEUE_0
            ? PRIME_WAVEFRONT_TRACE_QUEUE_1
            : PRIME_WAVEFRONT_TRACE_QUEUE_0;
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            targetQueue, recordIndex, continuation);
}

void primeWavefrontArea<IndirectReuse>(
        IndirectReuse indirectReuse,
        inout PrimeIntegratorContext context,
        uint launchIndex)
    where IndirectReuse : IPrimeIndirectReuse
{
    uint recordIndex =
            primeWavefrontQueuedPath(
                    PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    if (surface.hitKind != PRIME_HIT_SURFACE) {
        return;
    }

    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    if (!primeSecondarySamplesAreaLight(preparedSample)) {
        return;
    }
    PrimeRcVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return;
    }
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    float3 viewDirection = -path.rayDirection;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    if (!primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
        return;
    }
    if (primeWavefrontTransparentBranch(control)) {
        bool guideEnabled = primeWavefrontGuideEnabled(control);
        bool hasGuide = (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
        if (guideEnabled && !hasGuide && !primeClosureIsDiscreteOnly(closureTraits)) {
            return;
        }
    } else {
        PrimeIndirectReuseResult reuse = indirectReuse.query(
                context, path, surface, true);
        if (reuse.hit) {
            return;
        }
    }

    primeSetNumericalContext(context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    float3 contribution = path.throughput * primeEstimateDirectAreaLight(context,
            surface,
            viewDirection,
            primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY).xy,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_SECONDARY),
            volumeStack,
            PRIME_SECONDARY_LIGHT_FAMILY_PROBABILITY);
    if (any(contribution > float3(0.0))) {
        primeAccumulateDeferredDirectLight(
                context, pixel, control, contribution);
    }
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
}

void primeWavefrontSun(
        inout PrimeIntegratorContext context,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_SUN_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record = primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    PrimeWavefrontSunShadowWork work =
            primeLoadWavefrontSunShadowWork(recordIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeShadowTrace shadow = primeTracePreparedShadow(
            context,
            work.traceOrigin,
            work.direction,
            work.distance,
            work.startingExtinction0,
            work.startingExtinction1,
            work.startingMediumCount);
    float visibility = float(shadow.hitDistance >= PRIME_NRD_FP16_MAX);
    float3 contribution = work.contribution * shadow.transmittance * visibility;
    if (any(contribution > float3(0.0))) {
        primeAccumulateDeferredDirectLight(
                context, pixel, work.control, contribution);
    }
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
}

void primeWavefrontResolve(
        inout PrimeIntegratorContext context, uint2 launchIndex) {
    uint2 pixel = launchIndex;
    if (primeWavefrontPixelTransparent(pixel)) {
        return;
    }
    PrimeWavefrontTransportRecord primaryRecord =
            primeWavefrontPaths[primeWavefrontIndex(pixel, 0u)].transport;
    uint primaryDiagnostic =
            asuint(primaryRecord.throughputAndNumericalFlags.w);
    context.diagnostics.flags =
            primaryDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
    context.diagnostics.firstContext =
            (primaryDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                    & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
    PrimeIntegrationResult result = primeLoadWavefrontIntermediate(
            pixel, primaryRecord);
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    primeWriteRealtimeOutput(context.diagnostics, pixel, cameraSample, result);
}

void primeWavefrontTransparentResolve(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE, launchIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeWavefrontTransportRecord primaryRecord =
            primeWavefrontPaths[primeWavefrontIndex(pixel, 0u)].transport;
    uint primaryDiagnostic =
            asuint(primaryRecord.throughputAndNumericalFlags.w);
    context.diagnostics.flags =
            primaryDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
    context.diagnostics.firstContext =
            (primaryDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                    & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
    uint secondaryDiagnostic = asuint(
            primeWavefrontPaths[primeWavefrontIndex(pixel, 1u)]
                    .transport.throughputAndNumericalFlags.w);
    uint secondaryFlags =
            secondaryDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
    if (context.diagnostics.flags == 0u && secondaryFlags != 0u) {
        context.diagnostics.firstContext =
                (secondaryDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                        & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
    }
    context.diagnostics.flags |= secondaryFlags;

    PrimeIntegrationResult result =
            primeResolveTransparentWavefrontResult(pixel);
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    float4 reflectionPreviousVirtualPosition = float4(0.0, 0.0, 0.0, -1.0);
    if (primeTransparentGuideMode()
            != PRIME_PATH_TRANSPARENT_GUIDE_MODE_DISABLED) {
        PathState cameraPath = primeCameraPath(pixel, 0u, cameraSample);
        SurfaceInteraction primarySurface = primeTraceSurfaceWithoutReorder(
                primePush.cameraPosition, cameraPath.rayDirection);
        if (primarySurface.hitKind == PRIME_HIT_SURFACE
                && primeMaterialIsTransmissive(primarySurface.materialControl)) {
            PrimeRcVolumeStack volumeStack =
                    (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
                    ? primeCameraWaterVolumeStack()
                    : primeEmptyVolumeStack();
            PrimeTransparentGuideProbeResult probes =
                    primeTraceTransparentGuideProbes(context,
                            cameraPath,
                            primarySurface,
                            volumeStack,
                            primeMaximumBounces());
            result.transmissionGuides = primeGuideWithProbeSurface(
                    result.transmissionGuides, probes.transmission);
            if (probes.transmission.primaryHitKind == PRIME_HIT_SURFACE) {
                result.transmissionAnchorDistance =
                        probes.transmissionAnchorDistance;
            }
            result.reflectionGuides = primeGuideWithProbeSurface(
                    result.reflectionGuides, probes.reflection);
            if (probes.reflection.primaryHitKind == PRIME_HIT_SURFACE) {
                result.reflectionDirectionalGuide = false;
            }
            reflectionPreviousVirtualPosition =
                    probes.reflectionPreviousVirtualPosition;
        }
    }
    primeWriteRealtimeOutput(context.diagnostics,
            pixel,
            cameraSample,
            result,
            reflectionPreviousVirtualPosition);
}
