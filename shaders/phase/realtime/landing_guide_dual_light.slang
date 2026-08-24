#language slang 2026
module "phase/realtime/landing_guide_dual_light.slang";

import "prime_realtime_abi.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/realtime/landing_guide_dual_light.slang";

public void primePhase()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_TRACE_QUEUE_0);
    }
    primeShadeLandingGuideDualLightQueued(context, launchIndex);
}
