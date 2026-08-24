#language slang 2026
module "phase/realtime/sharc/training_light_none.slang";

import "prime_realtime_abi.slang";
import "state/realtime/queue.slang";
import "transport/realtime/sharc_training_light_none.slang";

public void primePhase()
{
    uint launchIndex = DispatchRaysIndex().x;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0);
    }
    primeShadeSharcTrainingNone(
            launchIndex,
            PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0);
}
