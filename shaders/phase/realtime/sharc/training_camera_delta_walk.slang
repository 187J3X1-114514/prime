#language slang 2026
module "phase/realtime/sharc/training_camera_delta_walk.slang";

import "prime_abi.slang";
import "prime_sharc_abi.slang";
import "service/trace/context.slang";
import "transport/realtime/sharc_training_camera_delta_walk.slang";
import "state/realtime/sharc_training.slang";

public void primePhase()
{
    uint2 coordinate = DispatchRaysIndex().xy;
    uint2 pixel = coordinate * PRIME_SHARC_TRAINING_CAMERA_GRID_SIZE
            + primeSharcTrainingCameraPhase(primeSharcFrame.updatePhase);
    if (any(pixel >= primePush.outputExtent)) {
        return;
    }
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeWalkSharcTrainingCameraDelta(context, pixel);
}
