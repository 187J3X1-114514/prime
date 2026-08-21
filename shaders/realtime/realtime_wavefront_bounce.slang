#include "realtime/realtime_wavefront_light.slang"
#include "realtime/realtime_wavefront_step.slang"
#include "realtime/transparent_light_event.slang"

struct PrimeFusedWavefrontLightResult {
    uint storedSunProbability;
    bool diagnosticsCommitted;
    bool retired;
};

struct PrimeTransparentLightEvent {
    uint type;
    uint storedSunProbability;
};

PrimeTransparentLightEvent primeClassifyTransparentLightEvent(
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control) {
    PrimeTransparentLightEvent event;
    event.type = PRIME_TRANSPARENT_LIGHT_EVENT_NONE;
    event.storedSunProbability = 0u;
    if (surface.hitKind != PRIME_HIT_SURFACE) {
        return event;
    }

    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    float3 viewDirection = -path.rayDirection;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    if (!primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
        return event;
    }

    bool guideEnabled = primeWavefrontGuideEnabled(control);
    bool hasGuide = (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    if (guideEnabled && !hasGuide) {
        if (!primeClosureIsDiscreteOnly(closureTraits)) {
            event.type = PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY;
        }
        return event;
    }

    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    float sunProbability = primeMixedWavefrontSunProbability(
            integrator, surface, viewDirection);
    event.storedSunProbability =
            primeStoredSunFamilyProbability(sunProbability);
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    event.type = primeSecondarySamplesSunLight(
            preparedSample, sunProbability)
            ? PRIME_TRANSPARENT_LIGHT_EVENT_SUN
            : PRIME_TRANSPARENT_LIGHT_EVENT_AREA;
    return event;
}

bool primeTransparentLightEventSamplesArea(PrimeTransparentLightEvent event) {
    return event.type == PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY
            || event.type == PRIME_TRANSPARENT_LIGHT_EVENT_AREA;
}

bool primePrepareTransparentAreaSample(
        inout PrimeIntegratorContext context,
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        SurfaceInteraction surface,
        PrimeTransparentLightEvent event,
        out float3 preparedThroughput,
        inout PrimeTransparentAreaSampleInput lightData) {
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        preparedThroughput = path.throughput;
        return false;
    }
    preparedThroughput = path.throughput;
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    lightData.position = surface.position;
    lightData.receiverNormal =
            primeAreaLightReceiverNormal(surface, -path.rayDirection);
    lightData.treeSample =
            event.type == PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY
            ? primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY)
            : primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY).xy;
    lightData.positionSample = primeSobolSample2D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
            PRIME_SAMPLE_DIMENSION_SECONDARY);
    lightData.numericalFlags = context.diagnostics.flags;
    lightData.numericalContext =
            context.diagnostics.firstContext;
    return true;
}

AreaLightSample primeExecuteTransparentAreaSample(
        inout PrimeIntegratorContext context,
        PrimeTransparentAreaSampleInput lightData) {
    PrimeNumericalDiagnostics diagnostics;
    diagnostics.flags = lightData.numericalFlags;
    diagnostics.firstContext =
            lightData.numericalContext;
    AreaLightSample area = primeSampleAreaLight(
            diagnostics,
            lightData.position,
            lightData.receiverNormal,
            lightData.treeSample,
            lightData.positionSample);
    context.diagnostics = diagnostics;
    return area;
}

PrimeFusedWavefrontLightResult primeShadeTransparentSunLightEvent(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        PrimeTransparentLightEvent event) {
    PrimeFusedWavefrontLightResult result;
    result.storedSunProbability = event.storedSunProbability;
    result.diagnosticsCommitted = false;
    result.retired = false;
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return result;
    }
    float3 viewDirection = -path.rayDirection;
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    float sunProbability = primeSunFamilyProbabilityFromStored(
            event.storedSunProbability);
    float3 direct = primeEstimateDirectSun(
            context,
            integrator,
            surface,
            viewDirection,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            volumeStack,
            sunProbability);
    float3 contribution = path.throughput * direct;
    if (any(contribution > float3(0.0))) {
        primeAccumulateDeferredDirectLight(
                context,
                pixel,
                asuint(record.rayDirectionAndDenoiserControl.w),
                contribution);
    }
    result.diagnosticsCommitted = true;
    return result;
}

PrimeFusedWavefrontLightResult primeShadeTransparentAreaLightEvent(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        PrimeTransparentLightEvent event,
        bool preparedArea,
        float3 preparedThroughput,
        AreaLightSample area) {
    PrimeFusedWavefrontLightResult result;
    result.storedSunProbability = event.storedSunProbability;
    result.diagnosticsCommitted = false;
    result.retired = false;
    if (!preparedArea) {
        result.diagnosticsCommitted =
                event.type == PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY;
        return result;
    }

    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    path.throughput = preparedThroughput;
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    float3 viewDirection = -path.rayDirection;
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);

    if (event.type == PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY) {
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
        PrimeDirectLightingSplit nonsun =
                primeEstimatePrimaryDirectPreparedAreaLight(
                        context,
                        surface,
                        viewDirection,
                        area,
                        volumeStack,
                        false);
        primeAccumulateTransparentPrimaryDirect(
                context,
                pixel,
                primeWavefrontTransmissionBranch(
                        asuint(record.rayDirectionAndDenoiserControl.w)),
                path.throughput
                        * (sun.lighting.diffuse * sun.visibility
                                + nonsun.diffuse),
                path.throughput
                        * (sun.lighting.specular * sun.visibility
                                + nonsun.specular));
        result.storedSunProbability = 0u;
        result.diagnosticsCommitted = true;
        return result;
    }

    float sunProbability = primeSunFamilyProbabilityFromStored(
            event.storedSunProbability);
    float3 direct = primeEstimateDirectPreparedAreaLight(
            context,
            surface,
            viewDirection,
            area,
            volumeStack,
            1.0 - sunProbability);
    float3 contribution = path.throughput * direct;
    if (any(contribution > float3(0.0))) {
        primeAccumulateDeferredDirectLight(
                context,
                pixel,
                asuint(record.rayDirectionAndDenoiserControl.w),
                contribution);
    }
    result.diagnosticsCommitted = true;
    return result;
}

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

void primeFinishTransparentWavefrontBounce(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        uint sourceQueue,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control,
        uint storedSunProbability) {
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
            storedSunProbability,
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
    PrimeTransparentLightEvent lightEvent = primeClassifyTransparentLightEvent(
            recordIndex, record, surface, control);
    bool areaPending = primeTransparentLightEventSamplesArea(lightEvent);
    primeAppendWavefrontContinuation(
            PrimePostTraceWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_AREA_QUEUE,
            recordIndex,
            areaPending);
    if (areaPending) {
        // Preserve the exact texture-resolved hit for the compact resource-specialized queue.
        primeStoreWavefrontSurface(recordIndex, surface);
        primeStoreWavefrontSunFamilyProbability(
                recordIndex, lightEvent.storedSunProbability);
        primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
        return;
    }

    uint storedSunProbability = 0u;
    if (lightEvent.type == PRIME_TRANSPARENT_LIGHT_EVENT_SUN) {
        PrimeNumericalDiagnostics shadeDiagnostics = context.diagnostics;
        PrimeFusedWavefrontLightResult light =
                primeShadeTransparentSunLightEvent(
                        context,
                        recordIndex,
                        record,
                        surface,
                        lightEvent);
        storedSunProbability = light.storedSunProbability;
        if (!light.diagnosticsCommitted) {
            context.diagnostics = shadeDiagnostics;
        }
    }
    primeFinishTransparentWavefrontBounce(
            context,
            recordIndex,
            sourceQueue,
            record,
            surface,
            control,
            storedSunProbability);
}

void primeWavefrontTransparentArea(
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    bool needsPrimary = primeWavefrontGuideEnabled(control)
            && (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u;
    PrimeTransparentLightEvent lightEvent;
    lightEvent.type = needsPrimary
            ? PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY
            : PRIME_TRANSPARENT_LIGHT_EVENT_AREA;
    lightEvent.storedSunProbability =
            primeLoadWavefrontSunFamilyProbability(recordIndex);

    PrimeNumericalDiagnostics shadeDiagnostics = context.diagnostics;
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    float3 preparedThroughput = float3(0.0);
    PrimeTransparentAreaSampleInput lightData = {};
    bool preparedArea = primePrepareTransparentAreaSample(
            context,
            path,
            primeWavefrontVolumeStack(record),
            surface,
            lightEvent,
            preparedThroughput,
            lightData);
    AreaLightSample area;
    area.light.direction = float3(0.0);
    area.light.distance = 0.0;
    area.light.radiance = float3(0.0);
    area.light.pdf = 0.0;
    area.light.isDelta = 0u;
    area.sectionIndex = PRIME_NO_LIGHT_INDEX;
    area.emitterIndex = PRIME_NO_LIGHT_INDEX;
    area.uv = float2(0.0);
    if (preparedArea) {
        area = primeExecuteTransparentAreaSample(context, lightData);
    }
    PrimeFusedWavefrontLightResult light =
            primeShadeTransparentAreaLightEvent(
                    context,
                    recordIndex,
                    record,
                    surface,
                    lightEvent,
                    preparedArea,
                    preparedThroughput,
                    area);
    if (!light.diagnosticsCommitted) {
        context.diagnostics = shadeDiagnostics;
    }
    primeFinishTransparentWavefrontBounce(
            context,
            recordIndex,
            sourceQueue,
            record,
            surface,
            control,
            light.storedSunProbability);
}
