#language slang 2026
module "phase/offline/area_shadow.slang";

import "contract/offline/shader_record.slang";
import "contract/offline/area_request.slang";
import "service/reconstruct/nrd/common.slang";
import "contract/trace/shadow_payload.slang";
import "service/trace/context.slang";
import "state/offline/contract.slang";
import "state/offline/queue.slang";
import "state/offline/transport.slang";
import "policy/offline/queue.slang";
import "transport/direct/shadow.slang";
import "transport/offline/area_shadow.slang";
import "bsdf/compact/contract/volume.slang";

public void primePhase() {
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeOfflineShaderRecord.control >> 8u) & 1u;
    if (launchIndex == 0u) {
        primeResetOfflineQueue(sourceQueue);
    }
    uint pathIndex = primeOfflineQueuedPath(sourceQueue, launchIndex);
    uint areaControl = asuint(
            primeOfflinePaths[pathIndex].areaContributionAndValid.w);
    if ((areaControl & PRIME_OFFLINE_AREA_VALID) != 0u) {
        PrimeOfflineTransportRecord transport = primeOfflinePaths[pathIndex].transport;
        PrimeOfflineAreaRequest request;
        request.direction = primeOfflinePaths[pathIndex].areaDirectionAndDistance.xyz;
        request.distance = primeOfflinePaths[pathIndex].areaDirectionAndDistance.w;
        request.contribution = primeOfflinePaths[pathIndex].areaContributionAndValid.xyz;
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
    // Retain the previous receiver normal while clearing request validity and medium depth.
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
