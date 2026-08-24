#language slang 2026
module "phase/realtime/delta_walk.slang";

import "prime_realtime_abi.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/realtime/delta_walk.slang";

public void primePhase()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_TRACE_QUEUE_0);
    }
    primeWavefrontDeltaWalk(context, launchIndex);
}
