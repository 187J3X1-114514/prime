#include "prime_realtime_abi.slang"
#if defined(PRIME_SHARC_QUERY)
#include "prime_sharc_abi.slang"
#endif
#if defined(PRIME_ENABLE_SUBGROUP_QUEUE)
#endif
#if defined(PRIME_ENABLE_SER)
#endif
#include "core/common.slang"
#include "core/atmosphere_rt.slang"
#include "core/nrd_common.slang"
#include "rt/shadow_payload.slang"

#include "core/sampling.slang"
import "core/transparent_checkerboard.slang";
#include "bsdf/adapter/bsdf.slang"
#include "lighting/lights.slang"

#if defined(PRIME_SHARC_QUERY)
#define SHARC_UPDATE 0
#define SHARC_QUERY 1
#include "lighting/sharc.slang"
#endif

#include "integrator/integrator.slang"
#include "core/camera.slang"
#include "realtime/transparent_guide_probe.slang"
#include "realtime/wavefront_output.slang"
#include "realtime/wavefront_state.slang"

struct PrimeWavefrontShaderRecord {
    uint control;
};

[[vk::shader_record]]
ConstantBuffer<PrimeWavefrontShaderRecord> primeWavefrontShaderRecord;

void primeInitializeTransparentWavefront(
        inout PrimeIntegratorContext context,
        uint2 pixel,
        PathState path,
        PrimeRcVolumeStack volumeStack,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        uint maximumBounces,
        out uint queuedPathIndex,
        out bool queueContinuation) {
    queuedPathIndex = primeWavefrontIndex(pixel, 0u);
    queueContinuation = false;
    PrimeIntegrationResult initialResult = primeEmptyIntegrationResult();
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
        return;
    }

    float3 viewDirection = -path.rayDirection;
    primeAccumulate(context,
            initialResult.reflectionSpecularRadiance,
            primeEvaluateHitEmission(context, path, surface, false));
    float surfaceLinearRoughness = primeSurfaceLinearRoughness(surface);
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    bool supportsSolidAngle = primeClosureSupports(
            closureTraits, PRIME_MEASURE_SOLID_ANGLE);
    bool discreteOnlyInterface = primeClosureIsDiscreteOnly(closureTraits);
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    if (supportsSolidAngle) {
        primeSetNumericalContext(context.diagnostics,
                PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
        PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(context,
                integrator,
                surface,
                viewDirection,
                primeSobolSample2D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                        PRIME_SAMPLE_DIMENSION_PRIMARY),
                volumeStack,
                true);
        primeAccumulate(context,
                initialResult.radiance.diffuse,
                path.throughput * sun.lighting.diffuse * sun.visibility);
        primeAccumulate(context,
                initialResult.reflectionSpecularRadiance,
                path.throughput * sun.lighting.specular * sun.visibility);
        PrimeDirectLightingSplit nonsun = primeEstimatePrimaryNonsunDirect(context,
                surface,
                viewDirection,
                preparedSample,
                volumeStack,
                true);
        initialResult.guides.primaryAreaDiffuse =
                path.throughput * nonsun.diffuse;
        initialResult.guides.primaryAreaSpecular =
                path.throughput * nonsun.specular;
        primeAccumulate(context,
                initialResult.radiance.diffuse,
                initialResult.guides.primaryAreaDiffuse);
        primeAccumulate(context,
                initialResult.reflectionSpecularRadiance,
                initialResult.guides.primaryAreaSpecular);
        if (any(initialResult.guides.primaryAreaDiffuse > float3(0.0))
                || any(initialResult.guides.primaryAreaSpecular > float3(0.0))) {
            initialResult.guides.primaryAreaDirection = nonsun.direction;
        }
    }

    primeSetNumericalContext(context.diagnostics,
            PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
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
            surface.adjacentInterfaceControl);
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
            primeSetQueuedPsrGuide(
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
            primeAppendQueuedPsrDiscrete(
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
            queuedPathIndex = pathIndex;
            queueContinuation = true;
        }
        primeStoreTransparentBranchIntermediate(
                pixel, transmissionBranch, branchResult);
    }
}

void primeWavefrontHead(
        inout PrimeIntegratorContext context, uint2 launchIndex) {
    uint2 pixel = launchIndex;
    uint recordIndex = primeWavefrontIndex(pixel);
    uint queuedPathIndex = recordIndex;
    bool queueContinuation = false;
    uint maximumBounces = primeMaximumBounces();
    primeSetNumericalContext(context.diagnostics, PRIME_NUMERICAL_STAGE_CAMERA, 0u);

    if (maximumBounces == 0u) {
        float2 cameraSample = primeRealtimeCameraSample(pixel);
        PathState path = primeCameraPath(pixel, 0u, cameraSample);
        PrimeRcVolumeStack volumeStack =
                (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
                ? primeCameraWaterVolumeStack()
                : primeEmptyVolumeStack();
        PrimeDenoiserState denoiserState = primeInitialDenoiserState();
        PrimeIntegrationResult result = primeEmptyIntegrationResult();
        PrimeWavefrontPathRecord record = primeMakeWavefrontRecord(context.diagnostics,
                path,
                volumeStack,
                denoiserState,
                result.guides.primaryMaterialFlags,
                false);
        primeWavefrontPaths[primeWavefrontIndex(pixel, 0u)] = record;
        primeWavefrontPaths[primeWavefrontIndex(pixel, 1u)] = record;
        primeStoreWavefrontArea(pixel, result.guides);
        primeStoreWavefrontIntermediate(pixel, result);
    } else {
        float2 traceCameraSample = primeRealtimeCameraSample(pixel);
        float3 traceDirection = primeCameraRayDirection(pixel, traceCameraSample);
        primeSetNumericalContext(context.diagnostics, PRIME_NUMERICAL_STAGE_TRACE, 0u);
        SurfaceInteraction surface =
                primeTraceSurface(context, primePush.cameraPosition, traceDirection);

        // Build transport, medium and denoiser state only after SER has executed the primary hit.
        // This keeps the largest realtime records out of the implementation's reorder spill set.
        float2 cameraSample = primeRealtimeCameraSample(pixel);
        PathState path = primeCameraPath(pixel, 0u, cameraSample);
        IntegratorRecord integrator;
        integrator.sunDirectionIntensity = float4(
                primePush.sunDirection,
                ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
        integrator.environmentRadiance = float4(0.0);
        PrimeRcVolumeStack volumeStack =
                (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
                ? primeCameraWaterVolumeStack()
                : primeEmptyVolumeStack();
        PrimeDenoiserState denoiserState = primeInitialDenoiserState();
        PrimeIntegrationResult result = primeEmptyIntegrationResult();
        if (surface.hitKind == PRIME_HIT_SURFACE
                && primeMaterialIsTransmissive(surface.materialControl)) {
            primeInitializeTransparentWavefront(context,
                    pixel,
                    path,
                    volumeStack,
                    integrator,
                    surface,
                    maximumBounces,
                    queuedPathIndex,
                    queueContinuation);
        } else {
            bool continuation = primeIntegrateWavefrontSurface(context,
                    path,
                    integrator,
                    volumeStack,
                    denoiserState,
                    result,
                    surface,
                    0u);
            continuation = continuation && path.bounce < maximumBounces;
            PrimeWavefrontPathRecord record = primeMakeWavefrontRecord(context.diagnostics,
                    path,
                    volumeStack,
                    denoiserState,
                    result.guides.primaryMaterialFlags,
                    continuation);
            uint pathIndex = primeWavefrontIndex(pixel, 0u);
            primeWavefrontPaths[pathIndex] = record;
            queuedPathIndex = pathIndex;
            queueContinuation = continuation;
            primeSetWavefrontActive(record.transport, false);
            primeWavefrontPaths[primeWavefrontIndex(pixel, 1u)] = record;
            primeStoreWavefrontArea(pixel, result.guides);
            primeStoreWavefrontIntermediate(pixel, result);
        }
    }
    // Every head invocation reaches this collective exactly once, including inactive paths.
    primeAppendWavefrontContinuation(
            PRIME_WAVEFRONT_TRACE_QUEUE_0,
            queuedPathIndex,
            queueContinuation);
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
    SurfaceInteraction surface = primeTraceSurfaceClassified(context,
            traceOrigin, rayDirection, pathClass);
    primeStoreWavefrontSurface(recordIndex, surface);
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
    // Trace may cross a SER invocation-repack boundary. Keep queue publication scalar until
    // compaction moves to a separate dispatch with a well-defined subgroup population.
    primeAppendWavefrontPath(PRIME_WAVEFRONT_SHADE_QUEUE, recordIndex);
    if (surface.hitKind == PRIME_HIT_SURFACE) {
        primeAppendWavefrontPath(PRIME_WAVEFRONT_AREA_QUEUE, recordIndex);
    }
}

void primeWavefrontShade(
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue) {
    uint recordIndex =
            primeWavefrontQueuedPath(
                    PRIME_WAVEFRONT_SHADE_QUEUE, launchIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    if (primeSurfaceEmitterIndex(surface) != PRIME_NO_LIGHT_INDEX
            && !primePreviousCannotUseMis(path)) {
        path.previousLightNormal =
                primeLoadWavefrontPreviousLightNormal(recordIndex);
    }
    PrimeRcVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    uint maximumBounces = primeMaximumBounces();
    bool queueContinuation;

    if (primeWavefrontTransparentBranch(control)) {
        bool hadGuide =
                (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
        bool guideEnabled =
                (control & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
        bool needsPsr = guideEnabled && !hadGuide;
        bool transmissionBranch = primeWavefrontTransmissionBranch(control);
        PrimeTransparentBranchResult branchResult =
                primeLoadTransparentBranchActiveIntermediate(
                        pixel, transmissionBranch, control);
        PrimeQueuedPsrState psrState =
                needsPsr
                        ? primeLoadWavefrontPsrState(recordIndex, record)
                        : primeEmptyQueuedPsrState();
        bool hasGuide =
                hadGuide;
        bool diffusePath =
                (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
        uint guideBounce =
                (control >> PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT)
                        & PRIME_WAVEFRONT_BYTE_MASK;
        bool directionalGuide = primeWavefrontDirectionalGuide(control);
        bool hadDirectionalGuide = directionalGuide;
        bool continuation = primeIntegrateTransparentWavefrontSurface(context,
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
        continuation = continuation && path.bounce < maximumBounces;
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
        queueContinuation = continuation;
    } else {
        bool guideWasPending =
                (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u;
        PrimeIntegrationResult result =
                primeLoadWavefrontActiveIntermediate(pixel, control);
        PrimeDenoiserState denoiserState =
                primeWavefrontDenoiserState(control, result);
        bool continuation = primeIntegrateWavefrontSurface(context,
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
    }
    uint targetQueue = sourceQueue == PRIME_WAVEFRONT_TRACE_QUEUE_0
            ? PRIME_WAVEFRONT_TRACE_QUEUE_1
            : PRIME_WAVEFRONT_TRACE_QUEUE_0;
    primeAppendWavefrontContinuation(
            targetQueue, recordIndex, queueContinuation);
}

#if defined(PRIME_DEFER_SECONDARY_AREA_NEE)
void primeWavefrontArea(
        inout PrimeIntegratorContext context, uint launchIndex) {
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
#if defined(PRIME_SHARC_QUERY)
    } else {
        float3 ignoredContribution;
    if (primeQuerySharc(
            context, path, surface, true, ignoredContribution)) {
            return;
        }
#endif
    }

    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
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
            volumeStack);
    if (any(contribution > float3(0.0))) {
        primeAccumulateDeferredAreaLight(
                context, pixel, control, contribution);
    }
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
}
#endif

void primeWavefrontResolve(
        inout PrimeIntegratorContext context, uint2 launchIndex) {
    uint2 pixel = launchIndex;
    PrimeWavefrontTransportRecord primaryRecord =
            primeWavefrontPaths[primeWavefrontIndex(pixel, 0u)].transport;
    uint primaryDiagnostic =
            asuint(primaryRecord.throughputAndNumericalFlags.w);
    context.diagnostics.flags =
            primaryDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
    context.diagnostics.firstContext =
            (primaryDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                    & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
    bool transparentPrimary = primeWavefrontPixelTransparent(pixel);
    if (transparentPrimary) {
        uint secondaryDiagnostic = asuint(
                primeWavefrontPaths[primeWavefrontIndex(pixel, 1u)]
                        .transport.throughputAndNumericalFlags.w);
        uint secondaryFlags =
                secondaryDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
        if (context.diagnostics.flags == 0u && secondaryFlags != 0u) {
            context.diagnostics.firstContext =
                    (secondaryDiagnostic
                            >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                            & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
        }
        context.diagnostics.flags |= secondaryFlags;
    }
    PrimeIntegrationResult result = transparentPrimary
            ? primeResolveTransparentWavefrontResult(pixel)
            : primeLoadWavefrontIntermediate(
                    pixel,
                    primaryRecord);
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    float4 reflectionPreviousVirtualPosition = float4(0.0, 0.0, 0.0, -1.0);
    if (transparentPrimary
            && primeTransparentGuideMode()
                    != PRIME_PATH_TRANSPARENT_GUIDE_MODE_DISABLED) {
        PathState cameraPath = primeCameraPath(pixel, 0u, cameraSample);
        SurfaceInteraction primarySurface = primeTraceSurfaceWithoutReorder(context,
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
            primeApplyProbeSurfaceGuide(
                    result.transmissionGuides, probes.transmission);
            if (probes.transmission.primaryHitKind == PRIME_HIT_SURFACE) {
                result.transmissionAnchorDistance =
                        probes.transmissionAnchorDistance;
            }
            primeApplyProbeSurfaceGuide(
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
