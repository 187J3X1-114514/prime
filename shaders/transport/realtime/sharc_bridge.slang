#language slang 2026
module "transport/realtime/sharc_bridge.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "prime_realtime_abi.slang";
import "bsdf/compact/contract/volume.slang";
import "model/atmosphere/sky.slang";
import "policy/realtime/sharc_query.slang";
import "policy/runtime/config.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "state/realtime/transport.slang";
import "transport/surface/emission.slang";
import "transport/surface/medium.slang";
import "contract/path.slang";

public void primeWavefrontSharcBridgeQuery(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);

    float3 contribution = float3(0.0);
    if (surface.hitKind == PRIME_HIT_NONE) {
        IntegratorRecord integrator;
        integrator.sunDirectionIntensity = float4(
                primePush.sunDirection,
                ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
        integrator.environmentRadiance = float4(0.0);
        contribution = primeEvaluateEnvironmentContribution(
                context, path, integrator);
    } else if (surface.hitKind == PRIME_HIT_SURFACE) {
        PrimeOpenPbrVolumeStack volumeStack =
                primeWavefrontVolumeStack(record);
        if (primeApplySegmentMedium(context, path, surface, volumeStack)) {
            PrimeSharcIndirectReuse cache;
            PrimeIndirectReuseResult reuse = cache.query(
                    context, path, surface, true);
            contribution = reuse.hit
                    ? reuse.contribution
                    : path.throughput * primeEvaluateLocalHitEmission(
                            context, path, surface, true);
        }
    }
    if (any(contribution > float3(0.0))) {
        primeAccumulateDeferredDirectLight(
                context, pixel, control, contribution);
    }

    primeStoreWavefrontSurface(recordIndex, surface);
    primeStoreWavefrontTransportRecord(
            recordIndex, primeWavefrontWithActive(record, false));
}
