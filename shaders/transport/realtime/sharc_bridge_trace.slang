#language slang 2026
module "transport/realtime/sharc_bridge_trace.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "policy/realtime/queue.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "transport/realtime/trace.slang";

public void primeWavefrontSharcBridgeTrace(
        inout PrimeIntegratorContext context,
        uint launchIndex,
        uint sourceQueue,
        uint destinationQueue) {
    uint recordIndex = primeWavefrontQueuedPath(
            sourceQueue, launchIndex);
    uint control = asuint(
            primeWavefrontPaths[recordIndex]
                    .transport.rayDirectionAndDenoiserControl.w);
    SurfaceInteraction surface = primeFusedWavefrontTrace(
            context,
            recordIndex,
            primeWavefrontTransparentBranch(control));
    primeStoreWavefrontSurface(recordIndex, surface);
    primeAppendWavefrontContinuation(
            PrimePostTraceWavefrontContinuationAppender(),
            destinationQueue,
            recordIndex,
            true);
}
