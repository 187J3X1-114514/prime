#define SHARC_UPDATE 1
#define SHARC_QUERY 0

import "material/material.slang";
#include "lighting/sharc.slang"
#include "lighting/sharc_training_events.slang"

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint pathCount = primeSharcFrame.trainingWidth * primeSharcFrame.trainingHeight;
    uint pathIndex = dispatchThreadId.x;
    if (pathIndex >= pathCount) {
        return;
    }

    SharcParameters parameters = primeSharcParameters();
    SharcState state;
    SharcInit(state);
    float3 previousThroughput = float3(1.0);
    bool hasVertex = false;
    for (uint eventIndex = 0u;
            eventIndex < PRIME_SHARC_TRAINING_MAX_EVENTS;
            ++eventIndex) {
        PrimeSharcTrainingEvent event = primeLoadSharcTrainingEvent(
                pathIndex, eventIndex);
        if (event.type == PRIME_SHARC_TRAINING_EVENT_NONE) {
            continue;
        }
        if (hasVertex) {
            SharcSetThroughput(
                    state,
                    primeSharcTrainingThroughputRatio(
                            event.throughput, previousThroughput));
            SharcSetRadianceDirectionWeight(
                    state, event.incomingDirectionWeight);
        }
        if (event.type == PRIME_SHARC_TRAINING_EVENT_MISS) {
            SharcUpdateMiss(parameters, state, event.position);
            return;
        }
        if (event.type != PRIME_SHARC_TRAINING_EVENT_HIT) {
            return;
        }
        SharcHitData hit = primeSharcHitData(
                event.position,
                event.geometricNormal,
                event.radianceDirection,
                event.radianceDirectionWeight,
                event.materialDemodulation,
                event.emissive);
        if (!SharcUpdateHit(parameters, state, hit, event.direct, 0.0)) {
            return;
        }
        previousThroughput = event.throughput;
        hasVertex = true;
    }
}
