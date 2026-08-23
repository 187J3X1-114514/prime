#language slang 2026
module "phase/realtime/sharc/steady_area.slang";

import "prime_realtime_abi.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/realtime/steady.slang";
import "policy/realtime/sharc_query.slang";

public void primePhase()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_TRACE_QUEUE_0);
    }
    primeWavefrontSteadyConsume(
            PrimeIntegratedSharcTraining(),
            context,
            PRIME_STEADY_EVENT_AREA,
            PRIME_WAVEFRONT_TRACE_QUEUE_0,
            launchIndex);
}
