#language slang 2026
module "transport/realtime/sharc_training_none.slang";

import "prime_realtime_abi.slang";
import "state/realtime/queue.slang";
import "state/realtime/sharc_training.slang";

public void primeShadeSharcTrainingNone(
        uint launchIndex,
        uint sourceQueue) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    primeStoreSharcTrainingJobDirect(recordIndex, float3(0.0));
}
