#include "realtime/realtime_wavefront_common.slang"

void primePublishWavefrontShadePath(uint recordIndex, bool transparentBranch) {
#if defined(PRIME_ENABLE_SUBGROUP_QUEUE)
    // Both calls remain subgroup-uniform. Publish before traversal so SER cannot repack the
    // participating lanes between reservation and index storage.
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_SHADE_QUEUE,
            recordIndex,
            !transparentBranch);
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRANSPARENT_SHADE_QUEUE,
            recordIndex,
            transparentBranch);
#else
    uint shadeQueue = transparentBranch
            ? PRIME_WAVEFRONT_TRANSPARENT_SHADE_QUEUE
            : PRIME_WAVEFRONT_SHADE_QUEUE;
    primeAppendWavefrontPath(shadeQueue, recordIndex);
#endif
}

void primeWavefrontTransparentPrimaryDirect<AreaNee>(
        AreaNee areaNee,
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control)
    where AreaNee : IPrimeSecondaryAreaNee
{
    bool guideEnabled = primeWavefrontGuideEnabled(control);
    bool hasGuide = (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    if (!guideEnabled || hasGuide) {
        return;
    }
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return;
    }
    float3 viewDirection = -path.rayDirection;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    if (!primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)
            || primeClosureIsDiscreteOnly(closureTraits)) {
        return;
    }

    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
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
            false);
    float3 diffuseDirect =
            path.throughput * sun.lighting.diffuse * sun.visibility;
    float3 specularDirect =
            path.throughput * sun.lighting.specular * sun.visibility;
    if (areaNee.enabled()) {
        PrimeDirectLightingSplit nonsun = primeEstimatePrimaryNonsunDirect(
                context,
                surface,
                viewDirection,
                preparedSample,
                volumeStack,
                false);
        diffuseDirect += path.throughput * nonsun.diffuse;
        specularDirect += path.throughput * nonsun.specular;
    }
    primeAccumulateTransparentPrimaryDirect(
            context,
            pixel,
            primeWavefrontTransmissionBranch(control),
            diffuseDirect,
            specularDirect);
}

void primeWavefrontStep<AreaNee>(
        AreaNee areaNee,
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue)
    where AreaNee : IPrimeSecondaryAreaNee
{
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
    uint bounce = primeWavefrontPaths[recordIndex]
                    .transport.sourceAndPathControl.w
            & PRIME_WAVEFRONT_BYTE_MASK;
    float3 physicalOrigin = primeWavefrontPaths[recordIndex]
            .transport.physicalOriginAndPreviousBsdfPdf.xyz;
    uint2 sourcePrimitive = primeWavefrontPaths[recordIndex]
            .transport.sourceAndPathControl.xy;
    float3 rayDirection = primeWavefrontPaths[recordIndex]
            .transport.rayDirectionAndDenoiserControl.xyz;
    uint traceControl = asuint(
            primeWavefrontPaths[recordIndex]
                    .transport.rayDirectionAndDenoiserControl.w);
    bool transparentBranch = primeWavefrontTransparentBranch(traceControl);
    primePublishWavefrontShadePath(recordIndex, transparentBranch);
    uint pathClass = (traceControl & PRIME_WAVEFRONT_TRANSPARENT_BRANCH) == 0u
            ? 0u
            : (traceControl & PRIME_WAVEFRONT_TRANSMISSION_BRANCH) == 0u
                    ? 1u
                    : 2u;
    primeSetNumericalContext(context.diagnostics, PRIME_NUMERICAL_STAGE_TRACE, bounce);
    SurfaceInteraction surface = primeTraceSurfaceClassified(
            PrimeSurfaceTracePolicy(),
            physicalOrigin, rayDirection, sourcePrimitive, pathClass);
    primeStoreWavefrontSurface(recordIndex, surface);
    if (surface.hitKind == PRIME_HIT_SURFACE
            && primeWavefrontTransparentBranch(traceControl)) {
        PrimeWavefrontTransportRecord record =
                primeLoadWavefrontTransportRecord(recordIndex);
        primeWavefrontTransparentPrimaryDirect(
                areaNee, context, recordIndex, record, surface, traceControl);
    }
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
    // Surface-hit compaction depends on traversal output and therefore remains scalar after SER.
    if (surface.hitKind == PRIME_HIT_SURFACE) {
        primeAppendWavefrontPath(PRIME_WAVEFRONT_AREA_QUEUE, recordIndex);
    }
}
