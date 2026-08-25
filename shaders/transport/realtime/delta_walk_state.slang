#language slang 2026
module "transport/realtime/delta_walk_state.slang";

import "prime_abi_types.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "math/queued_psr.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "transport/guide/coincidence.slang";
import "model/material/defaults.slang";
import "service/bsdf/contract/albedo.slang";
import "service/trace/context.slang";
import "transport/realtime/trace.slang";
import "transport/realtime/delta_walk_guide.slang";

import "transport/scatter/contract.slang";
import "transport/scatter/delta.slang";
import "transport/scatter/path_update.slang";
import "model/realtime/transport.slang";
import "transport/surface/access.slang";
import "transport/surface/accumulate.slang";
import "transport/surface/medium.slang";
import "state/realtime/contract.slang";
import "state/realtime/guide.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/transparent.slang";
import "state/realtime/transport.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/realtime/ordinary.slang";
import "contract/realtime/transparent.slang";

public bool primeAdvanceOrdinaryDeltaWalk(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control) {
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    PrimeDenoiserState denoiserState =
            primeWavefrontDenoiserState(
                    control,
                    primeLoadWavefrontActiveIntermediate(pixel, control));
    PrimeOrdinarySample result =
            primeLoadWavefrontActiveIntermediate(pixel, control);

    if (denoiserState.hasPrimarySurface
            && path.bounce == denoiserState.primaryBounce + 1u) {
        float hitDistance = primeNrdSanitizeHitDistance(surface.t);
        if (denoiserState.diffusePath) {
            result.guides.diffuseHitDistance = hitDistance;
        } else {
            result.guides.specularHitDistance = hitDistance;
        }
    }
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return false;
    }

    float3 viewDirection = -path.rayDirection;
    primeAccumulateAfterPrimary(
            context,
            result,
            denoiserState.diffusePath,
            primeEvaluateDeltaHitEmission(context, path, surface));
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    float3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimeGuidedPathScatter guided = primeSampleGuidedDiscretePathSurface(
            context,
            surface,
            viewDirection,
            scatterSample,
            volumeStack,
            path.bounce);
    PrimePathScatter scatter = guided.scatter;
    BsdfSample bsdf = scatter.bsdf;
    volumeStack = scatter.volumeStack;
    if (!primeHasScatter(bsdf)) {
        return false;
    }
    if (!denoiserState.reachedSolidAngle) {
        if (!denoiserState.hasPrimarySurface) {
            denoiserState.specularAlbedoProduct += guided.albedos.specular;
            denoiserState.diffuseAlbedoProduct *= guided.albedos.diffuse;
            denoiserState.diffusePath = false;
            denoiserState.hasPrimarySurface = true;
            denoiserState.primaryBounce = path.bounce;
        } else {
            denoiserState.specularAlbedoProduct *= guided.albedos.specular;
        }
        result.guides.primarySpecularAlbedo =
                primeSanitizeDenoiseAlbedo(
                        denoiserState.specularAlbedoProduct);
    }

    if (!primeAdvancePathWithoutRoulette(
            context,
            path,
            surface,
            bsdf,
            0u)) {
        return false;
    }
    path.bounce++;
    PrimeWavefrontTransportRecord updated = primeMakeUpdatedWavefrontRecord(
            path,
            volumeStack,
            denoiserState,
            control,
            0u,
            true);
    primeStoreWavefrontTransportRecord(recordIndex, updated);
    primeStoreWavefrontPreviousLightNormal(
            recordIndex, path.previousLightNormal);
    primeStoreWavefrontActiveIntermediate(pixel, false, result);
    return true;
}

public bool primeAdvanceTransparentDeltaWalk(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control,
        uint completedTransitions,
        uint limit) {
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    bool transmissionBranch = primeWavefrontTransmissionBranch(control);
    bool guideEnabled =
            (control & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
    bool hadGuide =
            (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    bool guideProbeFallback = primeWavefrontGuideProbeFallback(
            record.sourceAndPathControl.w);
    bool observesGuide = guideEnabled
            && !hadGuide
            && !guideProbeFallback;
    bool diffusePath =
            (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    uint guideBounce =
            (control >> PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT)
                    & PRIME_WAVEFRONT_BYTE_MASK;
    bool directionalGuide = primeWavefrontDirectionalGuide(control);
    PrimeTransparentBranchResult result =
            primeLoadTransparentBranchActiveIntermediate(
                    pixel, transmissionBranch, control);
    PrimeQueuedPsrState psrState = observesGuide
            ? primeLoadWavefrontPsrState(recordIndex, record)
            : primeEmptyQueuedPsrState();

    if (path.bounce == 1u) {
        result.firstHitDistance = primeNrdSanitizeHitDistance(surface.t);
    }
    if (hadGuide && path.bounce == guideBounce + 1u) {
        if (diffusePath) {
            result.guides.diffuseHitDistance =
                    primeNrdSanitizeHitDistance(surface.t);
        } else {
            result.guides.specularHitDistance =
                    primeNrdSanitizeHitDistance(surface.t);
        }
    }
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return false;
    }

    primeAccumulateTransparentBranch(
            context,
            result,
            guideEnabled ? diffusePath : transmissionBranch,
            primeEvaluateDeltaHitEmission(context, path, surface));
    float3 viewDirection = -path.rayDirection;
    float3 guidePreviousPosition = path.physicalOrigin;
    float3 guideThroughput = path.throughput;
    uint forkBounce = path.bounce;
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    PrimePathScatter scatter = primeSampleDiscretePathSurface(
            context,
            surface,
            viewDirection,
            primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            volumeStack);
    BsdfSample bsdf = scatter.bsdf;
    PrimePathScatter guideScatter;
    guideScatter.bsdf = primeInvalidBsdfSample();
    guideScatter.volumeStack = volumeStack;
    bool comparesGuide = primeTransparentGuideNeedsCanonicalComparison(
            observesGuide,
            primeMaterialIsTransmissive(surface.materialControl),
            bsdf);
    if (comparesGuide) {
        guideScatter = primeSampleDeltaWalkGuideContinuation(
                context, surface, viewDirection, volumeStack);
    }
    bool guideReusable = !comparesGuide
            || primeTransparentGuideContinuationReusable(
                    scatter.bsdf,
                    scatter.volumeStack,
                    guideScatter.bsdf,
                    guideScatter.volumeStack);
    bool forkGuide = primeTransparentGuideForkRequired(
            observesGuide,
            primeSurfaceHasMotion(surface),
            comparesGuide,
            guideReusable);
    guideProbeFallback = guideProbeFallback || forkGuide;
    if (!primeHasScatter(bsdf)) {
        return false;
    }
    if (observesGuide && !forkGuide) {
        psrState = primeAppendQueuedPsrDiscrete(
                psrState, path.physicalOrigin, surface, bsdf);
    }
    volumeStack = scatter.volumeStack;
    if (!primeAdvancePathWithoutRoulette(
            context,
            path,
            surface,
            bsdf,
            0u)) {
        return false;
    }
    path.bounce++;

    PrimeWavefrontTransportRecord updated =
            primeMakeUpdatedTransparentWavefrontRecord(
                    path,
                    volumeStack,
                    result,
                    psrState,
                    hadGuide,
                    diffusePath,
                    guideBounce,
                    directionalGuide,
                    control,
                    guideProbeFallback,
                    true);
    primeStoreWavefrontTransportRecord(recordIndex, updated);
    if (observesGuide && !forkGuide) {
        primeStoreWavefrontPsrState(recordIndex, psrState);
    } else {
        primeStoreWavefrontPreviousLightNormal(
                recordIndex, path.previousLightNormal);
    }
    primeStoreTransparentBranchActiveIntermediate(
            pixel,
            transmissionBranch,
            false,
            false,
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            result);
    if (forkGuide && !primeSurfaceHasMotion(surface)) {
        primeTraceDeltaWalkGuideFork(
                context,
                recordIndex,
                control,
                guidePreviousPosition,
                guideThroughput,
                forkBounce,
                surface,
                guideScatter,
                psrState,
                completedTransitions,
                limit);
    }
    return true;
}
