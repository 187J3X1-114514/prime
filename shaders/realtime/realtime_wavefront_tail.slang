#ifndef PRIME_REALTIME_WAVEFRONT_TAIL_SLANG
#define PRIME_REALTIME_WAVEFRONT_TAIL_SLANG

#include "realtime/realtime_wavefront.slang"

void primeWavefrontTail<IndirectReuse>(
        IndirectReuse indirectReuse,
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue)
    where IndirectReuse : IPrimeIndirectReuse
{
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeRcVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    uint maximumBounces = primeMaximumBounces();

    if (primeWavefrontTransparentBranch(control)) {
        bool hadGuide =
                (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
        bool guideEnabled =
                (control & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
        bool transmissionBranch = primeWavefrontTransmissionBranch(control);
        PrimeTransparentBranchResult branchResult =
                primeLoadTransparentBranchActiveIntermediate(
                        pixel, transmissionBranch, control);
        PrimeQueuedPsrState psrState = guideEnabled && !hadGuide
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
        bool firstTailStep = true;
        [loop]
        while (path.bounce < maximumBounces) {
            primeSetNumericalContext(
                    context.diagnostics,
                    PRIME_NUMERICAL_STAGE_TRACE,
                    path.bounce);
            SurfaceInteraction surface = primeTraceSurfaceWithoutReorder(
                    path.traceOrigin, path.rayDirection);
            if (firstTailStep
                    && primeSurfaceEmitterIndex(surface) != PRIME_NO_LIGHT_INDEX
                    && !primePreviousCannotUseMis(path)) {
                path.previousLightNormal =
                        primeLoadWavefrontPreviousLightNormal(recordIndex);
            }
            bool continuation = primeIntegrateTransparentWavefrontSurface(
                    PrimeImmediateSecondaryAreaNee(),
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
            firstTailStep = false;
            if (!continuation) {
                break;
            }
        }
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
                        false);
        primeStoreWavefrontTransportRecord(recordIndex, updatedRecord);
        primeStoreWavefrontPreviousLightNormal(
                recordIndex, path.previousLightNormal);
        primeStoreTransparentBranchActiveIntermediate(
                pixel,
                transmissionBranch,
                !hadGuide && hasGuide,
                !hadDirectionalGuide && directionalGuide,
                branchResult);
    } else {
        bool guideWasPending =
                (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u;
        PrimeIntegrationResult result =
                primeLoadWavefrontActiveIntermediate(pixel, control);
        PrimeDenoiserState denoiserState =
                primeWavefrontDenoiserState(control, result);
        bool firstTailStep = true;
        [loop]
        while (path.bounce < maximumBounces) {
            primeSetNumericalContext(
                    context.diagnostics,
                    PRIME_NUMERICAL_STAGE_TRACE,
                    path.bounce);
            SurfaceInteraction surface = primeTraceSurfaceWithoutReorder(
                    path.traceOrigin, path.rayDirection);
            if (firstTailStep
                    && primeSurfaceEmitterIndex(surface) != PRIME_NO_LIGHT_INDEX
                    && !primePreviousCannotUseMis(path)) {
                path.previousLightNormal =
                        primeLoadWavefrontPreviousLightNormal(recordIndex);
            }
            bool continuation = primeIntegrateWavefrontSurface(
                    PrimeImmediateSecondaryAreaNee(),
                    indirectReuse,
                    context,
                    path,
                    integrator,
                    volumeStack,
                    denoiserState,
                    result,
                    surface,
                    recordIndex);
            firstTailStep = false;
            if (!continuation) {
                break;
            }
        }
        PrimeWavefrontTransportRecord updatedRecord =
                primeMakeUpdatedWavefrontRecord(
                        context.diagnostics,
                        path,
                        volumeStack,
                        denoiserState,
                        control,
                        0u,
                        false);
        primeStoreWavefrontTransportRecord(recordIndex, updatedRecord);
        primeStoreWavefrontPreviousLightNormal(
                recordIndex, path.previousLightNormal);
        primeStoreWavefrontActiveIntermediate(
                pixel, guideWasPending, result);
    }
}

#endif
