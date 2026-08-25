#language slang 2026
module "transport/offline/context.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "state/path.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
import "state/realtime/medium.slang";
import "service/bsdf/support/volume_transition.slang";
import "state/offline/area.slang";
import "state/offline/contract.slang";
import "state/offline/transport.slang";
import "transport/offline/surface.slang";
import "bsdf/compact/contract/volume.slang";

public IntegratorRecord primeOfflineIntegrator() {
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    return integrator;
}

public PrimeOpenPbrVolumeStack primeOfflineInitialVolumeStack() {
    return (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
}

public PrimeOfflinePathRecord primeMakeOfflineRecord(
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        float3 radiance,
        float primaryDistance,
        float3 primaryAlbedo,
        uint primaryMaterialFlags,
        PrimeOfflineAreaRequest request,
        bool enabled) {
    PrimeOfflinePathRecord record;
    record.transport = primeMakeOfflineTransport(path, volumeStack, enabled);
    if (request.valid && request.startingMediumCount > volumeStack.count) {
        // Area visibility starts before the event; retain the released old top medium without
        // growing the fixed path ABI.
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
                    primePathPreviousLightNormal(path.control),
                    request.valid,
                    request.startingMediumCount)));
    record.areaTargetEmitter = uint4(request.targetEmitter, 0u, 0u);
    return record;
}
