#language slang 2026
module "phase/realtime/sharc/bridge_trace.slang";

import "prime_realtime_abi.slang";
import "contract/realtime/shader_record.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/realtime/sharc_bridge_trace.slang";

public void primePhase()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    bool reverse = (primeWavefrontShaderRecord.control & 1u) != 0u;
    uint sourceQueue = reverse
            ? PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1
            : PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0;
    uint destinationQueue = reverse
            ? PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0
            : PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(sourceQueue);
    }
    primeWavefrontSharcBridgeTrace(
            context, launchIndex, sourceQueue, destinationQueue);
}
