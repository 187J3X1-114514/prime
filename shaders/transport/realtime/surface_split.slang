#language slang 2026
module "transport/realtime/surface_split.slang";

import "policy/realtime/landing.slang";
import "policy/realtime/direct.slang";
import "policy/realtime/queue.slang";
import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "service/bsdf/contract/sample.slang";
import "contract/realtime/guide.slang";
import "model/atmosphere/sky.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "math/queued_psr.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "model/material/defaults.slang";
import "service/bsdf/dielectric/primary.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/bsdf/support/conversion.slang";
import "service/trace/context.slang";
import "transport/direct/policy.slang";
import "transport/direct/primary.slang";
import "model/realtime/transport.slang";
import "transport/guide/queued_psr.slang";
import "transport/scatter/ordinary.slang";
import "transport/scatter/path_update.slang";
import "transport/scatter/surface.slang";
import "transport/surface/access.slang";
import "transport/surface/accumulate.slang";
import "transport/surface/emission.slang";
import "transport/surface/medium.slang";
import "state/realtime/contract.slang";
import "state/realtime/guide.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/signal.slang";
import "state/realtime/surface.slang";
import "state/realtime/steady_transition.slang";
import "state/realtime/transparent.slang";
import "state/realtime/transport.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";
import "policy/transport/path.slang";
import "contract/realtime/ordinary.slang";
import "contract/realtime/transparent.slang";

public struct PrimeWavefrontContinuations {
    public uint firstPathIndex;
    public uint secondPathIndex;
    public bool firstActive;
    public bool secondActive;
    public bool firstDeltaWalk;
    public bool secondDeltaWalk;
    public bool transparent;
};

public PrimeWavefrontContinuations primeInitializeTransparentWavefront<AreaNee>(
        AreaNee areaNee,
        inout PrimeIntegratorContext context,
        uint2 pixel,
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        IntegratorRecord integrator,
        SurfaceInteraction surface,
        PrimeOrdinarySample initialResult,
        uint maximumBounces)
    where AreaNee : IPrimeSecondaryAreaNee
{
    PrimeWavefrontContinuations queued;
    queued.firstPathIndex = primeWavefrontIndex(pixel, 0u);
    queued.secondPathIndex = primeWavefrontIndex(pixel, 1u);
    queued.firstActive = false;
    queued.secondActive = false;
    queued.firstDeltaWalk = false;
    queued.secondDeltaWalk = false;
    queued.transparent = true;
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        PrimeDenoiserState emptyState = primeInitialDenoiserState();
        PrimeWavefrontPathRecord emptyRecord = primeMakeWavefrontRecord(path,
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
    float3 reflectionDiffuseRadiance = float3(0.0);
    float3 reflectionSpecularRadiance = initialResult.radiance.specular;
    initialResult.radiance.specular = float3(0.0);
    primeAccumulate(context,
            reflectionSpecularRadiance,
            primeEvaluateHitEmission(context, path, surface, false));
    float surfaceLinearRoughness = primeSurfaceLinearRoughness(surface);
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack, primeUsesAirGap());
    bool discreteOnlyInterface = primeClosureIsDiscreteOnly(closureTraits);
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
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
            primeUsesAirGap());
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
    bool primaryReflectionOnly = primeTransparentPrimaryReflectionOnly(
            discreteOnlyInterface,
            primeHasScatter(primarySample.paths.transmission.bsdfSample),
            primeHasScatter(primarySample.paths.reflection.bsdfSample));
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
                : reflectionDiffuseRadiance;
        branchResult.specularRadiance = transmissionBranch
                ? initialResult.radiance.specular
                : reflectionSpecularRadiance;
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
        bool guideProbeFallback = guideEnabled
                && hasScatter
                && !hasGuide
                && primeSurfaceHasMotion(surface);
        bool diffusePath = transmissionBranch;
        uint guideBounce = path.bounce;

        if (hasScatter) {
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
            continuation = primeAdvanceRealtimePath(context,
                    branchPath,
                    surface,
                    bsdf,
                    preparedSample,
                    branchDiscreteOnly,
                    true);
        }
        if (continuation) {
            branchPath.bounce = path.bounce + 1u;
        }
        bool deltaWalk = continuation
                && (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u;
        if (continuation && !deltaWalk) {
            primeEnterSteadyAfterNee(branchPath);
        }
        PrimeWavefrontPathRecord record =
                primeMakeTransparentWavefrontRecord(branchPath,
                selected.volumeStack,
                branchResult,
                psrState,
                transmissionBranch,
                guideEnabled,
                hasGuide,
                diffusePath,
                guideBounce,
                primaryReflectionOnly,
                guideProbeFallback,
                continuation);
        if (continuation && !deltaWalk) {
            record.transport = primeEnterSteadyRecord(record.transport);
        }
        uint pathIndex = primeWavefrontIndex(pixel, branch);
        primeWavefrontPaths[pathIndex] = record;
        if (transmissionBranch) {
            queued.firstActive = continuation;
            queued.firstDeltaWalk = deltaWalk;
        } else {
            queued.secondActive = continuation;
            queued.secondDeltaWalk = deltaWalk;
        }
        primeStoreTransparentBranchIntermediate(
                pixel,
                transmissionBranch,
                hasGuide,
                primaryReflectionOnly,
                branchResult);
    }
    return queued;
}

public void primeSplitVisibleSurface<AreaNee>(
        AreaNee areaNee,
        inout PrimeIntegratorContext context,
        uint launchIndex)
    where AreaNee : IPrimeSecondaryAreaNee
{
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_PRIMARY_QUEUE, launchIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
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
    continuations.firstDeltaWalk = false;
    continuations.secondDeltaWalk = false;
    continuations.transparent = false;

    if (surface.hitKind == PRIME_HIT_SURFACE
            && primeMaterialIsTransmissive(surface.materialControl)) {
        PrimeOrdinarySample initialResult =
                primeLoadWavefrontIntermediate(pixel, recordIndex, record);
        continuations =
                primeInitializeTransparentWavefront(
                        areaNee,
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
        PrimeOrdinarySample result =
                primeLoadWavefrontIntermediate(pixel, recordIndex, record);
        bool continuation = primeIntegrateWavefrontSurface(
                PrimeLandingTransportPhase(),
                PrimeStagedSecondarySunNee(),
                areaNee,
                PrimeNoIndirectReuse(),
                context,
                path,
                integrator,
                volumeStack,
                denoiserState,
                result,
                surface,
                true,
                recordIndex);
        bool deltaWalk = continuation
                && (path.flags & PRIME_PATH_PREVIOUS_DISCRETE) != 0u;
        if (continuation && !deltaWalk) {
            primeEnterSteadyAfterNee(path);
        }
        PrimeWavefrontPathRecord updated = primeMakeWavefrontRecord(
                path,
                volumeStack,
                denoiserState,
                result.guides,
                continuation);
        if (continuation && !deltaWalk) {
            updated.transport = primeEnterSteadyRecord(updated.transport);
        }
        primeWavefrontPaths[recordIndex] = updated;
        primeStoreWavefrontArea(pixel, result.guides);
        primeStoreWavefrontIntermediate(pixel, result);
        continuations.firstActive = continuation;
        continuations.firstDeltaWalk = deltaWalk;
    }
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0,
            continuations.firstPathIndex,
            continuations.firstActive && !continuations.firstDeltaWalk);
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0,
            continuations.secondPathIndex,
            continuations.secondActive && !continuations.secondDeltaWalk);
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRACE_QUEUE_0,
            continuations.firstPathIndex,
            continuations.firstDeltaWalk || continuations.secondDeltaWalk);
}
