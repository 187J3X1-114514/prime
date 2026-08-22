#ifndef PRIME_REALTIME_WAVEFRONT_PRIMARY_CHAIN_STATE_SLANG
#define PRIME_REALTIME_WAVEFRONT_PRIMARY_CHAIN_STATE_SLANG

#include "realtime/realtime_wavefront_delta_trace.slang"
#include "realtime/realtime_wavefront_primary_chain_guide.slang"

bool primeAdvanceOrdinaryPrimaryDelta(
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
    PrimeIntegrationResult result =
            primeLoadWavefrontActiveIntermediate(pixel, control);

    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_SURFACE, path.bounce);
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
    float surfaceLinearRoughness = primeSurfaceLinearRoughness(surface);
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    float3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimeGuidedPathScatter guided = primeSampleGuidedPathSurface(
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
    primeRecordDirection(context.diagnostics, bsdf.direction);
    primeRecordNonnegative(context.diagnostics, bsdf.response);
    primeRecordNonnegative(context.diagnostics, bsdf.pdf);
    primeRecordNonnegative(context.diagnostics, bsdf.relativeEta);

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
                primeSanitizeObservedDenoiseAlbedo(
                        context, denoiserState.specularAlbedoProduct);
    }

    if (!primeAdvancePathWithoutRoulette(
            context,
            path,
            surface,
            bsdf,
            0u,
            surfaceLinearRoughness)) {
        return false;
    }
    path.bounce++;
    PrimeWavefrontTransportRecord updated = primeMakeUpdatedWavefrontRecord(
            context.diagnostics,
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

bool primeAdvanceTransparentPrimaryDelta(
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

    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_SURFACE, path.bounce);
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
    float surfaceLinearRoughness = primeSurfaceLinearRoughness(surface);
    float3 guidePreviousPosition = path.physicalOrigin;
    float3 guideThroughput = path.throughput;
    uint forkBounce = path.bounce;
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_BSDF_SAMPLE, path.bounce);
    PrimePathScatter scatter = primeSamplePathSurface(
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
        guideScatter = primeSamplePrimaryChainGuideContinuation(
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
    primeRecordDirection(context.diagnostics, bsdf.direction);
    primeRecordNonnegative(context.diagnostics, bsdf.response);
    primeRecordNonnegative(context.diagnostics, bsdf.pdf);
    primeRecordNonnegative(context.diagnostics, bsdf.relativeEta);
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
            0u,
            surfaceLinearRoughness)) {
        return false;
    }
    path.bounce++;

    PrimeWavefrontTransportRecord updated =
            primeMakeUpdatedTransparentWavefrontRecord(
                    context.diagnostics,
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
        primeTracePrimaryChainGuideFork(
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

#endif
