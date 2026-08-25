#language slang 2026
module "phase/realtime/steady_dual_light_advance.slang";

import "prime_realtime_abi.slang";
import "policy/realtime/sharc.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/realtime/steady.slang";

public void primePhase()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_TRACE_QUEUE_0);
    }
    primeWavefrontSteadyConsume(
            PrimeNoWavefrontSharcTraining(),
            context,
            PRIME_STEADY_EVENT_DUAL,
            PRIME_WAVEFRONT_TRACE_QUEUE_0,
            launchIndex);
}
