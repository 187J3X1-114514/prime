import "prime_offline_abi.slang";

import "core/atmosphere_rt.slang";
import "core/numerical_diagnostics.slang";
import "core/nrd_common.slang";
import "core/render_config.slang";
import "rt/shadow_payload.slang";

import "core/sampling.slang";
#include "bsdf/adapter/bsdf.slang"
import "lighting/lights.slang";
#include "integrator/integrator.slang"
import "core/camera.slang";
#include "offline/wavefront_state.slang"
#include "offline/offline_integrator.slang"
#include "offline/offline_output.slang"

#if defined(PRIME_ENABLE_SUBGROUP_QUEUE)
typealias PrimeOfflineContinuationAppender =
        PrimeSubgroupOfflineContinuationAppender;
#else
typealias PrimeOfflineContinuationAppender =
        PrimeScalarOfflineContinuationAppender;
#endif

struct PrimeOfflineShaderRecord {
    uint control;
};

[[vk::shader_record]]
ConstantBuffer<PrimeOfflineShaderRecord> primeOfflineShaderRecord;

IntegratorRecord primeOfflineIntegrator() {
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    return integrator;
}

PrimeOpenPbrVolumeStack primeOfflineInitialVolumeStack() {
    return (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
}

PrimeOfflinePathRecord primeMakeOfflineRecord(
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        float3 radiance,
        float primaryDistance,
        float3 primaryAlbedo,
        uint primaryMaterialFlags,
        PrimeOfflineAreaRequest request,
        bool enabled) {
    PrimeOfflinePathRecord record;
    record.transport = primeMakeOfflineTransport(
            path, volumeStack, enabled);
    if (request.valid && request.startingMediumCount > volumeStack.count) {
        // Deferred Area visibility starts before the BSDF event, while transport stores the
        // post-event stack. An exiting event frees exactly the old top slot; retain its RGB
        // extinction there so Area can reconstruct the pre-event medium without growing ABI.
        PrimeOpenPbrVolume startingMedium;
        startingMedium.extinction = request.startingExtinction;
        startingMedium.albedo = float3(0.0);
        startingMedium.anisotropy = 0.0;
        startingMedium.ior = 1.0;
        uint startingIndex = request.startingMediumCount - 1u;
        if (startingIndex == 0u) {
            record.transport.medium0 = primePackWavefrontMedium(startingMedium);
        } else {
            record.transport.medium1 = primePackWavefrontMedium(startingMedium);
        }
    }
    record.radianceAndPrimaryDistance = float4(radiance, primaryDistance);
    record.primaryAlbedoAndMaterialFlags = float4(
            primaryAlbedo, asfloat(primaryMaterialFlags));
    record.areaDirectionAndDistance = float4(request.direction, request.distance);
    record.areaContributionAndValid = float4(
            request.contribution,
            asfloat(primeOfflineAreaControl(
                    path.previousLightNormal,
                    request.valid,
                    request.startingMediumCount)));
    record.areaTargetEmitter = uint4(request.targetEmitter, 0u, 0u);
    return record;
}

void primeOfflineWavefrontHead(
        inout PrimeIntegratorContext context, uint2 launchIndex) {
    uint2 pixel = launchIndex;
    uint pathIndex = primeOfflineIndex(pixel);
    float2 cameraSample = primeOfflineCameraSample(launchIndex);
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
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_TRACE, 0u);
    SurfaceInteraction surface = primeTraceSurface(
            primePush.cameraPosition, traceDirection);

    // Construct the full path, medium and accumulation state after SER. Only the camera sample
    // and trace inputs belong in the reorder spill set.
    PathState path = primeCameraPath(pixel, 0u, cameraSample);
    PrimeOpenPbrVolumeStack volumeStack = primeOfflineInitialVolumeStack();
    float3 radiance = float3(0.0);
    float primaryDistance = -1.0;
    float3 primaryAlbedo = float3(1.0);
    uint primaryMaterialFlags = 0u;
    PrimeOfflineAreaRequest request = primeEmptyOfflineAreaRequest();
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
    request = step.areaRequest;
    bool continuation = step.continuation
            && path.bounce < maximumBounces;
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

void primeOfflineWavefrontStep(
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue) {
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
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_TRACE, bounce);
    SurfaceInteraction surface = primeTraceSurface(
            traceOrigin,
            traceDirection,
            traceSource);

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
    uint primaryMaterialFlags = asuint(
            record.primaryAlbedoAndMaterialFlags.w);
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
    bool continuation = step.continuation;
    continuation = continuation && path.bounce < primeMaximumBounces();
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

void primeOfflineWavefrontArea(
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue) {
    uint pathIndex = primeOfflineQueuedPath(sourceQueue, launchIndex);
    uint areaControl = asuint(
            primeOfflinePaths[pathIndex].areaContributionAndValid.w);
    if ((areaControl & PRIME_OFFLINE_AREA_VALID) != 0u) {
        PrimeOfflineTransportRecord transport =
                primeOfflinePaths[pathIndex].transport;
        PrimeOfflineAreaRequest request;
        request.direction =
                primeOfflinePaths[pathIndex].areaDirectionAndDistance.xyz;
        request.distance =
                primeOfflinePaths[pathIndex].areaDirectionAndDistance.w;
        request.contribution =
                primeOfflinePaths[pathIndex].areaContributionAndValid.xyz;
        request.valid = true;
        PrimeOpenPbrVolumeStack storedMedia = primeOfflineVolumeStack(transport);
        request.startingMediumCount = (areaControl
                        >> PRIME_OFFLINE_AREA_MEDIUM_SHIFT)
                & PRIME_OFFLINE_AREA_MEDIUM_MASK;
        request.startingExtinction = request.startingMediumCount > 0u
                ? storedMedia.values[request.startingMediumCount - 1u].extinction
                : float3(0.0);
        request.targetEmitter = primeOfflinePaths[pathIndex].areaTargetEmitter.xy;
        PrimeShadowTrace shadow = primeTraceOfflineAreaShadow(
                context,
                transport.physicalOriginAndPreviousBsdfPdf.xyz,
                transport.sourceAndPathControl.xy,
                request,
                storedMedia);
        if (shadow.hitDistance >= PRIME_NRD_FP16_MAX) {
            primeOfflinePaths[pathIndex].radianceAndPrimaryDistance.xyz +=
                    request.contribution * shadow.transmittance;
        }
    }
    // Only validity and starting-medium depth are consumed. Retain the previous receiver normal;
    // the cold request payload is overwritten before it can become valid again.
    primeOfflinePaths[pathIndex].areaContributionAndValid.w = asfloat(
            areaControl & (PRIME_OFFLINE_LIGHT_NORMAL_MASK
                    << PRIME_OFFLINE_LIGHT_NORMAL_SHIFT));
    bool active = (asuint(primeOfflinePaths[pathIndex]
                    .transport.rayDirectionAndControl.w)
            & PRIME_OFFLINE_CONTROL_ACTIVE) != 0u;
    primeAppendOfflineContinuation(
            PrimeOfflineContinuationAppender(),
            sourceQueue ^ 1u,
            pathIndex,
            active);
}

void primeOfflineWavefrontResolve(uint2 launchIndex) {
    uint2 pixel = launchIndex;
    primeWriteOfflineOutput(
            pixel,
            primeOfflineCameraSample(launchIndex),
            primeOfflinePaths[primeOfflineIndex(pixel)]);
}
