#language slang 2026
module "phase/realtime/sharc/steady_sun.slang";

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
        primeResetWavefrontQueue(PRIME_WAVEFRONT_AREA_QUEUE);
    }
    primeWavefrontSteadyConsume(
            PrimeIntegratedSharcTraining(),
            context,
            PRIME_STEADY_EVENT_SUN,
            PRIME_WAVEFRONT_AREA_QUEUE,
            launchIndex);
}
