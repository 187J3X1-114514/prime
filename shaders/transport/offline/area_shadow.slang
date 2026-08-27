#language slang 2026
module "transport/offline/area_shadow.slang";

import "prime_abi_types.slang";
import "bsdf/compact/contract/flags.slang";
import "contract/offline/area_request.slang";
import "model/material/transparent.slang";
import "service/trace/context.slang";
import "transport/direct/shadow.slang";
import "bsdf/compact/contract/volume.slang";

public PrimeShadowTrace primeTraceOfflineAreaShadow(
        inout PrimeIntegratorContext context,
        float3 physicalPosition,
        uint2 sourcePrimitive,
        PrimeOfflineAreaRequest request,
        PrimeOpenPbrVolumeStack startingVolumeStack) {
    uint startingMediumCount = min(
            request.startingMediumCount, PRIME_OPENPBR_MAX_VOLUME_STACK_SIZE);
    float3 startingExtinction0 = startingMediumCount > 0u
            ? primeShadowCanonicalExtinction(
                    startingVolumeStack.values[0].extinction)
            : float3(0.0);
    float3 startingExtinction1 = startingMediumCount > 1u
            ? primeShadowCanonicalExtinction(
                    startingVolumeStack.values[1].extinction)
            : float3(0.0);
    return primeTracePreparedShadow(
            context,
            physicalPosition,
            request.direction,
            request.distance,
            sourcePrimitive,
            request.targetEmitter,
            startingExtinction0,
            startingExtinction1,
            startingMediumCount);
}
