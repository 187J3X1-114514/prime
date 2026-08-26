#language slang 2026
module "transport/realtime/tail.slang";

import "prime_abi.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "contract/path.slang";
import "contract/realtime/ordinary.slang";
import "contract/realtime/transparent.slang";
import "math/sampling.slang";
import "math/queued_psr.slang";
import "model/atmosphere/sky.slang";
import "model/realtime/transport.slang";
import "policy/realtime/steady.slang";
import "policy/runtime/config.slang";
import "service/trace/context.slang";
import "service/trace/surface.slang";
import "state/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/guide.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/transparent.slang";
import "state/realtime/transport.slang";
import "transport/direct/policy.slang";
import "transport/scatter/surface.slang";
import "transport/scatter/transparent.slang";
import "transport/surface/emission.slang";
import "bsdf/compact/contract/volume.slang";

public IntegratorRecord primeRealtimeTailIntegrator() {
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    return integrator;
}

public SurfaceInteraction primeTraceRealtimeTailSurface(
        PathState path, uint control) {
    bool transparent = primeWavefrontTransparentBranch(control);
    uint pathClass = !transparent
            ? 0u
            : primeWavefrontTransmissionBranch(control) ? 2u : 1u;
    return primeTraceSurfaceClassified(
            PrimeSurfaceTracePolicy(),
            path.physicalOrigin,
            path.rayDirection,
            path.sourcePrimitive,
            pathClass);
}

public void primeRunOrdinaryRealtimeTail(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        uint control) {
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    path.control = primePathWithPreviousLightNormal(
            path.control,
            primeLoadWavefrontPreviousLightNormal(recordIndex));
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    bool guideWasPending =
            (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u;
    PrimeOrdinarySample result =
            primeLoadWavefrontActiveIntermediate(pixel, control);
    PrimeDenoiserState denoiserState =
            primeWavefrontDenoiserState(control, result);
    bool continuation = true;

    [loop]
    while (continuation
            && primePathBounce(path.control) < primeMaximumBounces()) {
        SurfaceInteraction surface = primeTraceRealtimeTailSurface(
                path, control);
        continuation = primeIntegrateWavefrontSurface(
                PrimeSteadyTransportPhase(),
                PrimeImmediateSecondarySunNee(),
                PrimeImmediateSecondaryAreaNee(),
                PrimeNoIndirectReuse(),
                context,
                path,
                primeRealtimeTailIntegrator(),
                volumeStack,
                denoiserState,
                result,
                surface,
                false,
                recordIndex);
    }

    PrimeWavefrontTransportRecord updated = primeMakeUpdatedWavefrontRecord(
            path,
            volumeStack,
            denoiserState,
            control,
            0u,
            false);
    primeStoreWavefrontTransportRecord(recordIndex, updated);
    primeStoreWavefrontPreviousLightNormal(
            recordIndex, primePathPreviousLightNormal(path.control));
    primeStoreWavefrontActiveIntermediate(pixel, guideWasPending, result);
}

public void primeRunTransparentRealtimeTail(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        uint control) {
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    path.control = primePathWithPreviousLightNormal(
            path.control,
            primeLoadWavefrontPreviousLightNormal(recordIndex));
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    bool hadGuide =
            (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    bool guideEnabled = primeWavefrontGuideEnabled(control);
    bool transmissionBranch = primeWavefrontTransmissionBranch(control);
    PrimeTransparentBranchResult result =
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
    bool guideProbeFallback = primeWavefrontGuideProbeFallback(
            record.sourceAndPathControl.w);
    bool continuation = true;

    [loop]
    while (continuation
            && primePathBounce(path.control) < primeMaximumBounces()) {
        SurfaceInteraction surface = primeTraceRealtimeTailSurface(
                path, control);
        continuation = primeIntegrateTransparentWavefrontSurface(
                PrimeSteadyTransportPhase(),
                PrimeImmediateSecondarySunNee(),
                PrimeImmediateSecondaryAreaNee(),
                context,
                path,
                primeRealtimeTailIntegrator(),
                volumeStack,
                result,
                psrState,
                hasGuide,
                diffusePath,
                guideBounce,
                directionalGuide,
                guideProbeFallback,
                surface,
                transmissionBranch,
                guideEnabled,
                false,
                recordIndex);
    }

    PrimeWavefrontTransportRecord updated =
            primeMakeUpdatedTransparentWavefrontRecord(
                    path,
                    volumeStack,
                    result,
                    psrState,
                    hasGuide,
                    diffusePath,
                    guideBounce,
                    directionalGuide,
                    control,
                    guideProbeFallback,
                    false);
    primeStoreWavefrontTransportRecord(recordIndex, updated);
    primeStoreWavefrontPreviousLightNormal(
            recordIndex, primePathPreviousLightNormal(path.control));
    primeStoreTransparentBranchActiveIntermediate(
            pixel,
            transmissionBranch,
            !hadGuide && hasGuide,
            !hadDirectionalGuide && directionalGuide,
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            result);
}

public void primeRunRealtimeTail(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    if (primeWavefrontTransparentBranch(control)) {
        primeRunTransparentRealtimeTail(
                context, recordIndex, record, control);
    } else {
        primeRunOrdinaryRealtimeTail(
                context, recordIndex, record, control);
    }
}
