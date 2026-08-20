#include "realtime/realtime_wavefront_light.slang"
#include "realtime/realtime_wavefront_step.slang"

struct PrimeFusedWavefrontLightResult {
    uint storedSunProbability;
    bool diagnosticsCommitted;
    bool retired;
};

PrimeFusedWavefrontLightResult primeFusedWavefrontLight<
        LightNee, IndirectReuse, SharcTraining>(
        LightNee lightNee,
        IndirectReuse indirectReuse,
        SharcTraining sharcTraining,
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface)
    where LightNee : IPrimeWavefrontLightNee
    where IndirectReuse : IPrimeIndirectReuse
    where SharcTraining : IPrimeWavefrontSharcTraining
{
    PrimeFusedWavefrontLightResult result;
    result.storedSunProbability = 0u;
    result.diagnosticsCommitted = false;
    result.retired = false;
    if (surface.hitKind != PRIME_HIT_SURFACE) {
        return result;
    }

    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return result;
    }
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    if (!primeWavefrontTransparentBranch(control)) {
        PrimeIndirectReuseResult reuse = indirectReuse.query(
                context, path, surface, true);
        if (reuse.hit) {
            if (any(reuse.contribution > float3(0.0))) {
                primeAccumulateDeferredDirectLight(
                        context, pixel, control, reuse.contribution);
            }
            sharcTraining.finish(path);
            result.diagnosticsCommitted = true;
            result.retired = true;
            return result;
        }
    }

    float3 viewDirection = -path.rayDirection;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    if (!primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
        if (!primeWavefrontTransparentBranch(control)) {
            sharcTraining.recordHit(
                    context, path, surface, float3(0.0));
        }
        return result;
    }
    if (primeWavefrontTransparentBranch(control)) {
        bool guideEnabled = primeWavefrontGuideEnabled(control);
        bool hasGuide = (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
        if (guideEnabled && !hasGuide && !primeClosureIsDiscreteOnly(closureTraits)) {
            return result;
        }
    }

    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    float sunProbability = lightNee.sunProbability(
            integrator, surface, viewDirection);
    result.storedSunProbability =
            primeStoredSunFamilyProbability(sunProbability);

    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    float3 direct = lightNee.estimate(
            context,
            integrator,
            surface,
            viewDirection,
            preparedSample,
            volumeStack,
            sunProbability);
    if (!primeWavefrontTransparentBranch(control)) {
        sharcTraining.recordHit(context, path, surface, direct);
    }
    float3 contribution = path.throughput * direct;
    if (any(contribution > float3(0.0))) {
        primeAccumulateDeferredDirectLight(
                context, pixel, control, contribution);
    }
    result.diagnosticsCommitted = true;
    return result;
}

SurfaceInteraction primeFusedWavefrontTrace(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        bool transparent) {
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
    uint pathClass = !transparent
            ? 0u
            : primeWavefrontTransmissionBranch(traceControl) ? 2u : 1u;
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_TRACE, bounce);
    return primeTraceSurfaceClassified(
            PrimeSurfaceTracePolicy(),
            physicalOrigin,
            rayDirection,
            sourcePrimitive,
            pathClass);
}

void primeWavefrontOrdinaryBounce<IndirectReuse, SharcTraining>(
        IndirectReuse indirectReuse,
        SharcTraining sharcTraining,
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue)
    where IndirectReuse : IPrimeIndirectReuse
    where SharcTraining : IPrimeWavefrontSharcTraining
{
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    SurfaceInteraction surface = primeFusedWavefrontTrace(
            context, recordIndex, false);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    PrimeNumericalDiagnostics shadeDiagnostics = context.diagnostics;
    PrimeFusedWavefrontLightResult light = primeFusedWavefrontLight(
            PrimeMixedWavefrontLightNee(),
            indirectReuse,
            sharcTraining,
            context,
            recordIndex,
            record,
            surface);
    if (!light.diagnosticsCommitted) {
        // Staged Light did not publish diagnostics from its early-return paths.
        context.diagnostics = shadeDiagnostics;
    }

    bool continuation = false;
    if (light.retired) {
        record = primeWavefrontWithActive(record, false);
        primeStoreWavefrontTransportRecord(recordIndex, record);
        primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
    } else {
        uint2 pixel = primeWavefrontPixel(recordIndex);
        PathState path = primeWavefrontPath(pixel, record);
        PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
        uint control = asuint(record.rayDirectionAndDenoiserControl.w);
        IntegratorRecord integrator;
        integrator.sunDirectionIntensity = float4(
                primePush.sunDirection,
                ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
        integrator.environmentRadiance = float4(0.0);

        if (surface.hitKind == PRIME_HIT_NONE) {
            sharcTraining.recordMiss(context, path, integrator);
        }
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
        continuation = primeIntegrateWavefrontSurface(
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
                light.storedSunProbability,
                recordIndex);
        continuation = continuation && path.bounce < primeMaximumBounces();
        if (!continuation) {
            sharcTraining.finish(path);
        }
        PrimeWavefrontTransportRecord updatedRecord =
                primeMakeUpdatedWavefrontRecord(
                        context.diagnostics,
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
    }

    uint targetQueue = sourceQueue == PRIME_WAVEFRONT_TRACE_QUEUE_0
            ? PRIME_WAVEFRONT_TRACE_QUEUE_1
            : PRIME_WAVEFRONT_TRACE_QUEUE_0;
    primeAppendWavefrontContinuation(
            PrimePostTraceWavefrontContinuationAppender(),
            targetQueue,
            recordIndex,
            continuation);
}

void primeWavefrontTransparentBounce(
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    SurfaceInteraction surface = primeFusedWavefrontTrace(
            context, recordIndex, true);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    bool skipLight = surface.hitKind == PRIME_HIT_SURFACE
            && primeWavefrontTransparentPrimaryDirect(
                    PrimeStagedSecondaryAreaNee(),
                    context,
                    recordIndex,
                    record,
                    surface,
                    control);
    PrimeFusedWavefrontLightResult light;
    light.storedSunProbability = 0u;
    light.diagnosticsCommitted = false;
    light.retired = false;
    if (!skipLight) {
        PrimeNumericalDiagnostics shadeDiagnostics = context.diagnostics;
        light = primeFusedWavefrontLight(
                PrimeMixedWavefrontLightNee(),
                PrimeNoIndirectReuse(),
                PrimeNoWavefrontSharcTraining(),
                context,
                recordIndex,
                record,
                surface);
        if (!light.diagnosticsCommitted) {
            context.diagnostics = shadeDiagnostics;
        }
    }

    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
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
    bool guideProbeFallback = primeWavefrontGuideProbeFallback(
            record.sourceAndPathControl.w);
    bool continuation = primeIntegrateTransparentWavefrontSurface(
            PrimeStagedSecondarySunNee(),
            PrimeStagedSecondaryAreaNee(),
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
            guideProbeFallback,
            surface,
            transmissionBranch,
            guideEnabled,
            light.storedSunProbability,
            recordIndex);
    continuation = continuation && path.bounce < primeMaximumBounces();
    PrimeWavefrontTransportRecord updatedRecord =
            primeMakeUpdatedTransparentWavefrontRecord(
                    context.diagnostics,
                    path,
                    volumeStack,
                    branchResult,
                    psrState,
                    hasGuide,
                    diffusePath,
                    guideBounce,
                    directionalGuide,
                    control,
                    guideProbeFallback,
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
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            branchResult);
    uint targetQueue = sourceQueue == PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0
            ? PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1
            : PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0;
    primeAppendWavefrontContinuation(
            PrimePostTraceWavefrontContinuationAppender(),
            targetQueue,
            recordIndex,
            continuation);
}
