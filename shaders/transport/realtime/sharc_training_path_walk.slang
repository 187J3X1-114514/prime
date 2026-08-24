#language slang 2026
module "transport/realtime/sharc_training_path_walk.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "prime_realtime_abi.slang";
import "contract/path.slang";
import "math/sampling.slang";
import "model/material/defaults.slang";
import "model/realtime/transport.slang";
import "policy/realtime/queue.slang";
import "policy/runtime/config.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dispatch/sample.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/trace/context.slang";
import "service/trace/surface.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/sharc_training.slang";
import "state/realtime/surface.slang";
import "state/realtime/transport.slang";
import "transport/scatter/contract.slang";
import "transport/scatter/ordinary.slang";
import "transport/scatter/path_update.slang";
import "transport/surface/access.slang";
import "transport/surface/medium.slang";
import "bsdf/compact/contract/volume.slang";

public void primeStoreSharcTrainingJob(
        uint jobIndex,
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        SurfaceInteraction surface) {
    PrimeDenoiserState denoiserState = primeInitialDenoiserState();
    denoiserState.hasPrimarySurface = true;
    denoiserState.reachedSolidAngle = true;
    PrimeWavefrontPathRecord job;
    job.transport = primeMakeWavefrontTransportRecord(
            path, volumeStack, denoiserState, 0u, true);
    job.psrPacked = uint4(
            0u,
            0u,
            0u,
            (path.previousLightNormal & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
                    << PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT);
    primeWavefrontPaths[jobIndex] = job;
    primeStoreWavefrontSurface(jobIndex, surface);
    primeAppendWavefrontPath(PRIME_WAVEFRONT_PRIMARY_QUEUE, jobIndex);
}

public void primeWalkSharcTrainingPath(
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue,
        uint destinationQueue) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    uint trainingIndex = primeSharcTrainingPathIndexFromPixel(pixel);
    PathState path = primeWavefrontPath(pixel, record);
    path.previousLightNormal =
            primeLoadWavefrontPreviousLightNormal(recordIndex);
    PrimeOpenPbrVolumeStack volumeStack =
            primeWavefrontVolumeStack(record);
    uint jobCount = 0u;
    bool continuation = true;
    bool terminal = false;
    uint walkDepth = primeSharcTrainingPathWalkDepth();

    [loop]
    for (uint localBounce = 0u;
            localBounce < walkDepth && continuation;
            localBounce++) {
        SurfaceInteraction surface = primeTraceSurfaceClassified(
                PrimeSurfaceTracePolicy(),
                path.physicalOrigin,
                path.rayDirection,
                path.sourcePrimitive,
                0u);
        if (surface.hitKind == PRIME_HIT_NONE) {
            uint jobIndex = primeSharcTrainingJobIndex(
                    trainingIndex, jobCount);
            primeStoreSharcTrainingJob(
                    jobIndex, path, volumeStack, surface);
            jobCount++;
            continuation = false;
            terminal = true;
            break;
        }
        if (surface.hitKind != PRIME_HIT_SURFACE
                || !primeApplySegmentMedium(
                        context, path, surface, volumeStack)) {
            continuation = false;
            terminal = true;
            break;
        }

        uint jobIndex = primeSharcTrainingJobIndex(
                trainingIndex, jobCount);
        primeStoreSharcTrainingJob(
                jobIndex, path, volumeStack, surface);
        jobCount++;

        float3 viewDirection = -path.rayDirection;
        PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
                surface, viewDirection, volumeStack, primeUsesAirGap());
        bool discreteOnly = primeClosureIsDiscreteOnly(closureTraits);
        float surfaceLinearRoughness =
                primeMaterialIsTransmissive(surface.materialControl)
                ? primeSurfaceLinearRoughness(surface)
                : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
        PrimePreparedSampleBase preparedSample = primePrepareSampleBase(
                primeMakeSampleBase(path, path.bounce + 1u));
        PrimePathScatter scatter = primeSamplePathSurface(
                context,
                surface,
                viewDirection,
                primeSobolSample3D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                        PRIME_SAMPLE_DIMENSION_PRIMARY),
                volumeStack,
                primeUsesAirGap());
        volumeStack = scatter.volumeStack;
        continuation = primeHasScatter(scatter.bsdf)
                && primeAdvanceRealtimePath(
                        context,
                        path,
                        surface,
                        scatter.bsdf,
                        preparedSample,
                        discreteOnly,
                        surfaceLinearRoughness);
        if (continuation) {
            path.bounce++;
            continuation = path.bounce < primeMaximumBounces();
        }
        terminal = !continuation;
    }

    PrimeDenoiserState denoiserState = primeInitialDenoiserState();
    denoiserState.hasPrimarySurface = true;
    denoiserState.reachedSolidAngle = true;
    PrimeWavefrontPathRecord updated;
    updated.transport = primeMakeWavefrontTransportRecord(
            path, volumeStack, denoiserState, 0u, continuation);
    updated.psrPacked = uint4(
            jobCount | (terminal ? PRIME_SHARC_TRAINING_TERMINAL : 0u),
            0u,
            0u,
            (path.previousLightNormal & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
                    << PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT);
    primeWavefrontPaths[recordIndex] = updated;
    primeAppendWavefrontContinuation(
            PrimePostTraceWavefrontContinuationAppender(),
            destinationQueue,
            recordIndex,
            continuation);
}
