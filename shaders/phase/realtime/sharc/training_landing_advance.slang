#language slang 2026
module "phase/realtime/sharc/training_landing_advance.slang";

import "prime_realtime_abi.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/realtime/sharc_training_landing_advance.slang";

public void primePhase()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_PRIMARY_QUEUE);
    }
    primeAdvanceSharcTrainingLanding(context, launchIndex);
}
