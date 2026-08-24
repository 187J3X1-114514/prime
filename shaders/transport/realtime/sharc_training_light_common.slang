#language slang 2026
module "transport/realtime/sharc_training_light_common.slang";

import "prime_abi_types.slang";
import "state/realtime/contract.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/sharc_training.slang";

public PathState primeSharcTrainingLightPath(
        uint recordIndex,
        PrimeWavefrontTransportRecord record) {
    PathState path = primeWavefrontPath(
            primeSharcTrainingJobPixel(recordIndex), record);
    path.previousLightNormal =
            primeSharcTrainingJobPreviousLightNormal(recordIndex);
    return path;
}
