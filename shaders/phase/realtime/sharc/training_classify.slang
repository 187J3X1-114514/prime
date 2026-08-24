#language slang 2026
module "phase/realtime/sharc/training_classify.slang";

import "prime_realtime_abi.slang";
import "state/realtime/queue.slang";
import "transport/realtime/sharc_training_classify.slang";

public void primePhase()
{
    uint launchIndex = DispatchRaysIndex().x;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_PRIMARY_QUEUE);
    }
    primeClassifySharcTrainingLight(launchIndex);
}
