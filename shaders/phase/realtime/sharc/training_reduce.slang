#language slang 2026
module "phase/realtime/sharc/training_reduce.slang";

import "prime_abi.slang";
import "prime_sharc_abi.slang";
import "service/trace/context.slang";
import "state/realtime/sharc_training.slang";
import "transport/realtime/sharc_training_reduce.slang";

public void primePhase()
{
    uint2 coordinate = DispatchRaysIndex().xy;
    uint2 pixel = primeSharcTrainingPixelFromIndex(
            coordinate.y * primeSharcFrame.trainingWidth + coordinate.x);
    if (any(pixel >= primePush.outputExtent)) {
        return;
    }
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeReduceSharcTrainingChunk(
            context,
            coordinate.y * primeSharcFrame.trainingWidth + coordinate.x,
            pixel);
}
