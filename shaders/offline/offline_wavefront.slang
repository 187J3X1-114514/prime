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
#include "offline/offline_bsdf.slang"
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

PrimeRcVolumeStack primeOfflineInitialVolumeStack() {
    return (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
}

PrimeOfflinePathRecord primeMakeOfflineRecord(
        PathState path,
        PrimeRcVolumeStack volumeStack,
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
        PrimeRcVolume startingMedium;
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
    return record;
}

void primeOfflineWavefrontHead(
        inout PrimeIntegratorContext context, uint2 launchIndex) {
    uint2 pixel = launchIndex;
    uint pathIndex = primeOfflineIndex(pixel);
    float2 cameraSample = primeOfflineCameraSample(launchIndex);
    PathState path = primeCameraPath(pixel, 0u, cameraSample);
    PrimeRcVolumeStack volumeStack = primeOfflineInitialVolumeStack();
    float3 radiance = float3(0.0);
    float primaryDistance = -1.0;
    float3 primaryAlbedo = float3(1.0);
    uint primaryMaterialFlags = 0u;
    PrimeOfflineAreaRequest request = primeEmptyOfflineAreaRequest();
    uint maximumBounces = primeMaximumBounces();
    bool continuation = false;
    if (maximumBounces != 0u) {
        primeSetNumericalContext(
                context.diagnostics, PRIME_NUMERICAL_STAGE_TRACE, 0u);
        SurfaceInteraction surface = primeTraceSurface(
                primePush.cameraPosition, path.rayDirection);
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
        continuation = step.continuation;
        continuation = continuation && path.bounce < maximumBounces;
    }
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
    PrimeOfflineTransportRecord traceRecord =
            primeOfflinePaths[pathIndex].transport;
    uint bounce = asuint(traceRecord.traceOriginAndPathControl.w)
            & PRIME_OFFLINE_PATH_BYTE_MASK;
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_TRACE, bounce);
    SurfaceInteraction surface = primeTraceSurface(
            traceRecord.traceOriginAndPathControl.xyz,
            traceRecord.rayDirectionAndControl.xyz);

    PrimeOfflinePathRecord record = primeOfflinePaths[pathIndex];
    uint2 pixel = primeOfflinePixel(pathIndex);
    PathState path = primeOfflinePath(
            pixel,
            record.transport,
            primeOfflinePreviousLightNormal(record));
    PrimeRcVolumeStack volumeStack = primeOfflineVolumeStack(record.transport);
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
    PrimeOfflinePathRecord record = primeOfflinePaths[pathIndex];
    uint areaControl = asuint(record.areaContributionAndValid.w);
    if ((areaControl & PRIME_OFFLINE_AREA_VALID) != 0u) {
        PrimeOfflineAreaRequest request;
        request.direction = record.areaDirectionAndDistance.xyz;
        request.distance = record.areaDirectionAndDistance.w;
        request.contribution = record.areaContributionAndValid.xyz;
        request.valid = true;
        PrimeRcVolumeStack storedMedia = primeOfflineVolumeStack(record.transport);
        request.startingMediumCount = primeOfflineAreaStartingMediumCount(record);
        request.startingExtinction = request.startingMediumCount > 0u
                ? storedMedia.values[request.startingMediumCount - 1u].extinction
                : float3(0.0);
        PrimeShadowTrace shadow = primeTraceOfflineAreaShadow(
                context,
                record.transport.physicalOriginAndPreviousBsdfPdf.xyz,
                request,
                storedMedia);
        if (shadow.hitDistance >= PRIME_NRD_FP16_MAX) {
            record.radianceAndPrimaryDistance.xyz +=
                    request.contribution * shadow.transmittance;
        }
    }
    record = primeOfflineWithoutAreaRequest(record);
    primeOfflinePaths[pathIndex] = record;
    primeAppendOfflineContinuation(
            PrimeOfflineContinuationAppender(),
            sourceQueue ^ 1u,
            pathIndex,
            primeOfflineIsActive(record));
}

void primeOfflineWavefrontResolve(uint2 launchIndex) {
    uint2 pixel = launchIndex;
    primeWriteOfflineOutput(
            pixel,
            primeOfflineCameraSample(launchIndex),
            primeOfflinePaths[primeOfflineIndex(pixel)]);
}
