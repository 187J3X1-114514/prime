#include "realtime/realtime_wavefront_common.slang"

void primeWavefrontShade<AreaNee, IndirectReuse, SharcTraining>(
        AreaNee areaNee,
        IndirectReuse indirectReuse,
        SharcTraining sharcTraining,
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue)
    where AreaNee : IPrimeSecondaryAreaNee
    where IndirectReuse : IPrimeIndirectReuse
    where SharcTraining : IPrimeWavefrontSharcTraining
{
    uint recordIndex =
            primeWavefrontQueuedPath(
                    PRIME_WAVEFRONT_SHADE_QUEUE, launchIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    // The area stage consumes a SHARC hit and retires its record. The queued shade entry remains
    // structurally valid, but must not query or integrate the same vertex a second time.
    if (!primeWavefrontActive(record)) {
        return;
    }
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    uint maximumBounces = primeMaximumBounces();
    bool queueContinuation;

    if (surface.hitKind == PRIME_HIT_NONE) {
        sharcTraining.recordMiss(context, path, integrator);
    }

    if (areaNee.enabled()
            && primeSurfaceEmitterIndex(surface) != PRIME_NO_LIGHT_INDEX
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
            PrimeStagedSecondarySunNee(),
            areaNee,
            indirectReuse,
            context,
            path,
            integrator,
            volumeStack,
            denoiserState,
            result,
            surface,
            primeLoadWavefrontSunFamilyProbability(recordIndex),
            false,
            recordIndex);
    continuation = continuation && path.bounce < maximumBounces;
    if (!continuation) {
        sharcTraining.finish(path);
    }
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
