#language slang 2026
module "transport/realtime/sharc_training_landing.slang";

import "prime_abi_types.slang";
import "prime_realtime_abi.slang";
import "math/sampling.slang";
import "model/realtime/transport.slang";
import "policy/realtime/queue.slang";
import "policy/runtime/config.slang";
import "service/bsdf/dispatch/sample.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "state/realtime/transport.slang";
import "transport/scatter/path_update.slang";
import "transport/scatter/contract.slang";
import "transport/surface/access.slang";
import "transport/surface/medium.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";

public void primeSharcTrainingLandingScatter(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_PRIMARY_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack =
            primeWavefrontVolumeStack(record);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    bool continuation = primeApplySegmentMedium(
            context, path, surface, volumeStack);
    BsdfSample bsdf = primeInvalidBsdfSample();
    if (continuation) {
        PrimePreparedSampleBase preparedSample =
                primePrepareSampleBase(
                        primeMakeSampleBase(path, path.bounce + 1u));
        PrimePathScatter scatter = primeSamplePathSurface(
                context,
                surface,
                -path.rayDirection,
                primeSobolSample3D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                        PRIME_SAMPLE_DIMENSION_PRIMARY),
                volumeStack,
                primeUsesAirGap());
        bsdf = scatter.bsdf;
        volumeStack = scatter.volumeStack;
        continuation = primeHasScatter(bsdf)
                && primeAdvancePathWithoutRoulette(
                        context,
                        path,
                        surface,
                        bsdf,
                        0u,
                        primeSurfaceLinearRoughness(surface));
    }

    PrimeDenoiserState denoiserState = primeInitialDenoiserState();
    if (continuation) {
        path.bounce++;
        // This bootstrap vertex intentionally has no NEE job. The first cached suffix therefore
        // treats an emitter reached by the bridge as an uncompeted BSDF sample.
        path.flags |= PRIME_PATH_PREVIOUS_DISCRETE;
        denoiserState.hasPrimarySurface = true;
        denoiserState.reachedSolidAngle = true;
        denoiserState.diffusePath =
                (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
    }
    PrimeWavefrontPathRecord updated;
    updated.transport = primeMakeWavefrontTransportRecord(
            path, volumeStack, denoiserState, 0u, continuation);
    updated.transport = primeWavefrontSteadyState(
            updated.transport);
    updated.psrPacked = uint4(0u);
    primeWavefrontPaths[recordIndex] = updated;
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_TRACE_QUEUE_0,
            recordIndex,
            continuation);
}
