#define SHARC_UPDATE 1
#define SHARC_QUERY 0

import "material/material.slang";
#include "lighting/sharc.slang"
#include "lighting/sharc_training_records.slang"

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint pathCount = primeSharcFrame.trainingWidth * primeSharcFrame.trainingHeight;
    uint taskIndex = dispatchThreadId.x;
    uint taskCount = pathCount * PRIME_SHARC_TRAINING_ANCHOR_CAPACITY;
    if (taskIndex >= taskCount) {
        return;
    }
    uint pathIndex = taskIndex / PRIME_SHARC_TRAINING_ANCHOR_CAPACITY;
    uint anchor = taskIndex % PRIME_SHARC_TRAINING_ANCHOR_CAPACITY;

    PrimeSharcTrainingRecord record = primeLoadSharcTrainingRecord(
            pathIndex, anchor);
    if (!primeSharcTrainingActive(record.pathControl)
            || !primeSharcTrainingComplete(record.pathControl)
            || !primeSharcTrainingActive(record.anchorControl)) {
        return;
    }
    SharcState state;
    SharcInit(state);
    SharcHitData hit = primeSharcHitData(
            record.position,
            record.geometricNormal,
            record.radianceDirection,
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
