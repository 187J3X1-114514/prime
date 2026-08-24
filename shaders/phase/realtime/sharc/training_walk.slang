#language slang 2026
module "phase/realtime/sharc/training_walk.slang";

import "prime_realtime_abi.slang";
import "contract/realtime/shader_record.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/realtime/sharc_training_walk.slang";

public void primePhase()
{
    uint launchIndex = DispatchRaysIndex().x;
    bool reverse = (primeWavefrontShaderRecord.control & 1u) != 0u;
    uint sourceQueue = reverse
            ? PRIME_WAVEFRONT_TRACE_QUEUE_1
            : PRIME_WAVEFRONT_TRACE_QUEUE_0;
    uint destinationQueue = reverse
            ? PRIME_WAVEFRONT_TRACE_QUEUE_0
            : PRIME_WAVEFRONT_TRACE_QUEUE_1;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(sourceQueue);
        primeResetWavefrontQueue(PRIME_WAVEFRONT_PRIMARY_QUEUE);
    }
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeSharcTrainingWalk(
            context,
            launchIndex,
            sourceQueue,
            destinationQueue);
}
