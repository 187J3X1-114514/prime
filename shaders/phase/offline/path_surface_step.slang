#language slang 2026
module "phase/offline/path_surface_step.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "contract/offline/shader_record.slang";
import "policy/runtime/config.slang";
import "service/trace/context.slang";
import "service/trace/surface.slang";
import "state/offline/area.slang";
import "state/offline/contract.slang";
import "state/offline/index.slang";
import "state/offline/queue.slang";
import "state/offline/transport.slang";
import "transport/offline/context.slang";
import "transport/offline/surface.slang";
import "bsdf/compact/contract/volume.slang";

public void primePhase() {
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeOfflineShaderRecord.control >> 8u) & 1u;
    uint pathIndex = primeOfflineQueuedPath(sourceQueue, launchIndex);
    float3 traceOrigin = primeOfflinePaths[pathIndex]
            .transport.physicalOriginAndPreviousBsdfPdf.xyz;
    float3 traceDirection = primeOfflinePaths[pathIndex]
            .transport.rayDirectionAndControl.xyz;
    uint2 traceSource = primeOfflinePaths[pathIndex]
            .transport.sourceAndPathControl.xy;
    uint bounce = primeOfflinePaths[pathIndex]
            .transport.sourceAndPathControl.w
            & PRIME_OFFLINE_PATH_BYTE_MASK;
    SurfaceInteraction surface = primeTraceSurface(
            traceOrigin, traceDirection, traceSource);

    PrimeOfflinePathRecord record = primeOfflinePaths[pathIndex];
    uint2 pixel = primeOfflinePixel(pathIndex);
    PathState path = primeOfflinePath(
            pixel,
            record.transport,
            primeOfflinePreviousLightNormal(record));
    PrimeOpenPbrVolumeStack volumeStack = primeOfflineVolumeStack(record.transport);
    float3 radiance = record.radianceAndPrimaryDistance.xyz;
    float primaryDistance = record.radianceAndPrimaryDistance.w;
    float3 primaryAlbedo = record.primaryAlbedoAndMaterialFlags.xyz;
    uint primaryMaterialFlags = asuint(record.primaryAlbedoAndMaterialFlags.w);
    PrimeOfflineSurfaceStep surfaceStep = primeIntegrateOfflineSurface(
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
    PrimeOfflineAreaRequest request = surfaceStep.areaRequest;
    bool continuation = surfaceStep.continuation
            && primePathBounce(path.control) < primeMaximumBounces();
    primeOfflinePaths[pathIndex] = primeMakeOfflineRecord(
            path,
            volumeStack,
            radiance,
            primaryDistance,
            primaryAlbedo,
            primaryMaterialFlags,
            request,
            continuation);
}
