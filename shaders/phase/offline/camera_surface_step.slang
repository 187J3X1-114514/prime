#language slang 2026
module "phase/offline/camera_surface_step.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "service/camera/projection.slang";
import "policy/runtime/config.slang";
import "service/trace/context.slang";
import "service/trace/surface.slang";
import "state/offline/contract.slang";
import "state/offline/index.slang";
import "state/offline/queue.slang";
import "policy/offline/queue.slang";
import "transport/offline/context.slang";
import "transport/offline/surface.slang";
import "bsdf/compact/contract/volume.slang";

public void primePhase() {
    uint2 pixel = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint pathIndex = primeOfflineIndex(pixel);
    float2 cameraSample = primeOfflineCameraSample(pixel);
    uint maximumBounces = primeMaximumBounces();
    if (maximumBounces == 0u) {
        PathState emptyPath = primeCameraPath(pixel, 0u, cameraSample);
        primeOfflinePaths[pathIndex] = primeMakeOfflineRecord(
                emptyPath,
                primeOfflineInitialVolumeStack(),
                float3(0.0),
                -1.0,
                float3(1.0),
                0u,
                primeEmptyOfflineAreaRequest(),
                false);
        return;
    }

    float3 traceDirection = primeCameraRayDirection(pixel, cameraSample);
    SurfaceInteraction surface = primeTraceSurface(
            primePush.cameraPosition, traceDirection);

    // Construct full path state after SER; only camera and trace inputs cross traversal.
    PathState path = primeCameraPath(pixel, 0u, cameraSample);
    PrimeOpenPbrVolumeStack volumeStack = primeOfflineInitialVolumeStack();
    float3 radiance = float3(0.0);
    float primaryDistance = -1.0;
    float3 primaryAlbedo = float3(1.0);
    uint primaryMaterialFlags = 0u;
    PrimeOfflineSurfaceStep step = primeIntegrateOfflineSurface(
            context,
            path,
            primeOfflineIntegrator(),
            volumeStack,
            radiance,
            primaryDistance,
            primaryAlbedo,
            primaryMaterialFlags,
            surface,
            true);
    PrimeOfflineAreaRequest request = step.areaRequest;
    bool continuation = step.continuation && path.bounce < maximumBounces;
    primeOfflinePaths[pathIndex] = primeMakeOfflineRecord(
            path,
            volumeStack,
            radiance,
            primaryDistance,
            primaryAlbedo,
            primaryMaterialFlags,
            request,
            continuation);
    primeAppendOfflineContinuation(
            PrimeOfflineContinuationAppender(),
            0u,
            pathIndex,
            request.valid || continuation);
}
