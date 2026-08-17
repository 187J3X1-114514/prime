#define SHARC_UPDATE 1
#define SHARC_QUERY 0

import "material/material.slang";
#include "lighting/sharc.slang"
#include "lighting/sharc_training_records.slang"

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint pathCount = primeSharcFrame.trainingWidth * primeSharcFrame.trainingHeight;
    uint pathIndex = dispatchThreadId.x;
    if (pathIndex >= pathCount) {
        return;
    }

    PrimeSharcTrainingRecord record = primeLoadSharcTrainingRecord(pathIndex);
    if (!primeSharcTrainingActive(record.control)
            || !primeSharcTrainingComplete(record.control)) {
        return;
    }
    SharcState state;
    SharcInit(state);
    SharcHitData hit = primeSharcHitData(
            record.position,
            record.geometricNormal,
            float3(0.0, 0.0, 1.0),
            0.0,
            record.materialDemodulation,
            float3(0.0));
    SharcParameters parameters = primeSharcParameters();
    if (!SharcUpdateHit(parameters, state, hit, record.direct, 0.0)) {
        return;
    }
    if (any(record.tail != float3(0.0))) {
        SharcSetRadianceDirectionWeight(
                state, record.incomingDirectionWeight);
        SharcUpdateMiss(parameters, state, record.tail);
    }
}
