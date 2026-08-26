#language slang 2026
module "transport/realtime/fixed_bridge_trace.slang";

import "prime_abi_types.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "transport/realtime/trace.slang";

public void primeTraceFixedBridge(
        inout PrimeIntegratorContext context,
        uint sourceQueue,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    uint control = asuint(
            primeWavefrontPaths[recordIndex]
                    .transport.rayDirectionAndDenoiserControl.w);
    SurfaceInteraction surface = primeFusedWavefrontTrace(
            context,
            recordIndex,
            primeWavefrontTransparentBranch(control));
    primeStoreWavefrontSurface(recordIndex, surface);
}
