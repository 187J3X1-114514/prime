#language slang 2026
module "transport/realtime/camera_trace.slang";

import glsl;
import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "service/atmosphere/trace.slang";
import "service/camera/projection.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";
import "contract/realtime/guide.slang";
import "policy/queue/wavefront.slang";

import "service/bsdf/support/volume_transition.slang";
import "service/trace/context.slang";
import "service/trace/surface.slang";
import "model/realtime/transport.slang";
import "state/realtime/medium.slang";
import "state/realtime/contract.slang";
import "state/realtime/surface.slang";
import "state/realtime/queue.slang";
import "state/realtime/transport.slang";
import "state/realtime/signal.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";

import "contract/realtime/ordinary.slang";
import "service/reconstruct/ordinary.slang";

public void primeTraceRealtimeCamera(
        inout PrimeIntegratorContext context, uint2 launchIndex) {
    uint2 pixel = launchIndex;
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    uint maximumBounces = primeMaximumBounces();
    bool continuation = maximumBounces != 0u;
    uint pathIndex = primeWavefrontIndex(pixel, 0u);
    uint primaryHitKind = PRIME_HIT_NONE;
    if (continuation) {
        float3 traceDirection = primeCameraRayDirection(pixel, cameraSample);
        SurfaceInteraction surface = primeTraceSurfaceClassified(
                PrimeSurfaceTracePolicy(),
                primePush.cameraPosition,
                traceDirection,
                uint2(PRIME_NO_PRIMITIVE_INDEX),
                0u);
        primaryHitKind = surface.hitKind;
        primeStoreWavefrontSurface(pathIndex, surface);
    }

    // Build large transport records after SER so they do not enter the reorder spill set.
    PathState path = primeCameraPath(pixel, 0u, cameraSample);
    PrimeOpenPbrVolumeStack volumeStack =
            (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
    PrimeDenoiserState denoiserState = primeInitialDenoiserState();
    PrimeOrdinarySample result = primeEmptyOrdinarySample();
    PrimeWavefrontPathRecord record = primeMakeWavefrontRecord(
            path,
            volumeStack,
            denoiserState,
            result.guides,
            continuation);
    primeWavefrontPaths[pathIndex] = record;
    primeStoreWavefrontArea(pixel, result.guides);
    primeStoreWavefrontIntermediate(pixel, result);
    // SER may repack invocations. Queue publication after it must not use subgroup collectives.
    if (continuation) {
        primeAppendWavefrontPath(PRIME_WAVEFRONT_PRIMARY_QUEUE, pathIndex);
        if (primaryHitKind == PRIME_HIT_SURFACE) {
            primeAppendWavefrontPath(PRIME_WAVEFRONT_AREA_QUEUE, pathIndex);
        }
    }
}
