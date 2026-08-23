#ifndef PRIME_REALTIME_WAVEFRONT_ADVANCE_SLANG
#define PRIME_REALTIME_WAVEFRONT_ADVANCE_SLANG

#include "realtime/realtime_wavefront_trace.slang"

bool primeAdvanceOrdinaryWavefrontRecord(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control,
        uint storedSunProbability,
        bool skipRoulette) {
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
    bool guideWasPending =
            (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u;
    PrimeIntegrationResult result =
            primeLoadWavefrontActiveIntermediate(pixel, control);
    PrimeDenoiserState denoiserState =
            primeWavefrontDenoiserState(control, result);
    bool continuation = primeIntegrateWavefrontSurface(
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
            storedSunProbability,
            skipRoulette,
            recordIndex);
    continuation = continuation
            && (skipRoulette || path.bounce < primeMaximumBounces());
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
    return continuation;
}

bool primeAdvanceTransparentWavefrontRecord(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control,
        uint storedSunProbability,
        bool skipRoulette) {
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
            skipRoulette,
            recordIndex);
    bool integratedContinuation = continuation;
    continuation = continuation
            && (skipRoulette || path.bounce < primeMaximumBounces());
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
    return continuation;
}

#endif
