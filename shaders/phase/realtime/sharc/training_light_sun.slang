#language slang 2026
module "phase/realtime/sharc/training_light_sun.slang";

import "prime_realtime_abi.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/realtime/sharc_training_light_sun.slang";

public void primePhase()
{
    uint launchIndex = DispatchRaysIndex().x;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_AREA_QUEUE);
    }
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeShadeSharcTrainingSun(
            context,
            launchIndex,
            PRIME_WAVEFRONT_AREA_QUEUE);
}
