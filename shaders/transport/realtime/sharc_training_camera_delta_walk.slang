#language slang 2026
module "transport/realtime/sharc_training_camera_delta_walk.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "prime_realtime_abi.slang";
import "math/sampling.slang";
import "model/realtime/transport.slang";
import "policy/realtime/queue.slang";
import "policy/runtime/config.slang";
import "service/bsdf/support/volume_transition.slang";
import "service/camera/projection.slang";
import "service/trace/context.slang";
import "service/trace/surface.slang";
import "state/realtime/contract.slang";
import "state/realtime/medium.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "state/realtime/transport.slang";
import "transport/scatter/delta.slang";
import "transport/scatter/contract.slang";
import "transport/scatter/path_update.slang";
import "transport/surface/medium.slang";
import "transport/surface/access.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";

public void primePublishSharcTrainingLanding(
        uint pathIndex,
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        SurfaceInteraction surface) {
    PrimeDenoiserState denoiserState = primeInitialDenoiserState();
    PrimeWavefrontPathRecord record;
    record.transport = primeMakeWavefrontTransportRecord(
            path, volumeStack, denoiserState, 0u, true);
    record.psrPacked = uint4(0u);
    primeWavefrontPaths[pathIndex] = record;
    primeStoreWavefrontSurface(pathIndex, surface);
    primeAppendWavefrontContinuation(
            PrimePostTraceWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_PRIMARY_QUEUE,
            pathIndex,
            true);
}

public void primeWalkSharcTrainingCameraDelta(
        inout PrimeIntegratorContext context, uint2 pixel) {
    if (primeMaximumBounces() == 0u) {
        return;
    }
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    PathState path = primeCameraPath(pixel, 0u, cameraSample);
    PrimeOpenPbrVolumeStack volumeStack =
            (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
    uint pathIndex = primeWavefrontIndex(pixel, 0u);
    // A camera path that terminates before landing must not expose stale chunk metadata.
    primeWavefrontPaths[pathIndex].psrPacked = uint4(0u);
    uint limit = primeDeltaWalkLimit();

    [loop]
    for (uint transition = 0u; transition <= limit; ++transition) {
        SurfaceInteraction surface = primeTraceSurfaceClassified(
                PrimeSurfaceTracePolicy(),
                path.physicalOrigin,
                path.rayDirection,
                path.sourcePrimitive,
                0u);
        bool delta = surface.hitKind == PRIME_HIT_SURFACE
                && primeSurfaceIsDiscreteOnly(
                        surface, -path.rayDirection, volumeStack);
        if (!delta) {
            if (surface.hitKind == PRIME_HIT_SURFACE) {
                primePublishSharcTrainingLanding(
                        pathIndex, path, volumeStack, surface);
            }
            return;
        }
        if (transition == limit
                || !primeApplySegmentMedium(
                        context, path, surface, volumeStack)) {
            return;
        }

        PrimePreparedSampleBase preparedSample =
                primePrepareSampleBase(
                        primeMakeSampleBase(path, path.bounce + 1u));
        PrimePathScatter scatter = primeSampleDiscretePathSurface(
                context,
                surface,
                -path.rayDirection,
                primeSobolSample3D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                        PRIME_SAMPLE_DIMENSION_PRIMARY),
                volumeStack);
        if (!primeHasScatter(scatter.bsdf)) {
            return;
        }
        volumeStack = scatter.volumeStack;
        if (!primeAdvancePathWithoutRoulette(
                context,
                path,
                surface,
                scatter.bsdf,
                0u,
                primeSurfaceLinearRoughness(surface))) {
            return;
        }
        path.bounce++;
    }
}
