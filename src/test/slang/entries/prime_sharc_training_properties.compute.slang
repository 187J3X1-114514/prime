#include "core/prime_test_abi.slang"
#include "service/sharc/training_core.h"

static const uint PRIME_SHARC_TRAINING_INPUT_WORDS = 4u;
static const uint PRIME_SHARC_TRAINING_WITNESS_WORDS = 4u;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(
            caseIndex, PRIME_SHARC_TRAINING_INPUT_WORDS);
    uint4 inputControl = primeTestInput[inputOffset + 0u];
    float3 rootThroughput = asfloat(primeTestInput[inputOffset + 1u]).xyz;
    float3 target = asfloat(primeTestInput[inputOffset + 2u]).xyz;
    float3 localRadiance = asfloat(primeTestInput[inputOffset + 3u]).xyz;

    bool selected = inputControl.x != 0u;
    uint anchorIndex = inputControl.y % 12u;
    uint rootBounce = inputControl.z;
    bool complete = (inputControl.w & 1u) != 0u;
    bool initialized = (inputControl.w & 2u) != 0u;
    uint control = initialized
            ? primeSharcTrainingRootControl(rootBounce)
            : 0u;
    if (complete) control |= PRIME_SHARC_TRAINING_COMPLETE;

    bool bypass = primeSharcTrainingBypassesCacheValue(selected, control);
    bool expectedBypass = selected && !complete;
    uint2 trainingPixel = uint2(caseIndex & 1023u, caseIndex >> 10u);
    uint expectedPhase = (trainingPixel.y % PRIME_SHARC_TRAINING_GRID_SIZE)
            * PRIME_SHARC_TRAINING_GRID_SIZE
            + trainingPixel.x % PRIME_SHARC_TRAINING_GRID_SIZE;
    uint selectedPhaseCount = 0u;
    for (uint phase = 0u;
            phase < PRIME_SHARC_TRAINING_GRID_SIZE
                    * PRIME_SHARC_TRAINING_GRID_SIZE;
            phase++) {
        selectedPhaseCount += primeSharcTrainingPixelInPhase(
                trainingPixel, phase) ? 1u : 0u;
    }
    float3 rootDirection = primeSharcTrainingRootRadianceDirection(rootThroughput);
    float3 expectedRootDirection = -rootThroughput;
    float3 accumulated = primeSharcTrainingAccumulateTarget(
            target, rootThroughput, localRadiance);
    float3 expectedAccumulated = target + rootThroughput * localRadiance;
    float3 ratio = primeSharcTrainingRatio(accumulated, rootThroughput);
    float3 suffix = primeSharcTrainingSuffix(
            rootThroughput * localRadiance, rootThroughput);
    float3 expectedRatio = float3(
            rootThroughput.x > 0.0
                    ? expectedAccumulated.x / rootThroughput.x : 0.0,
            rootThroughput.y > 0.0
                    ? expectedAccumulated.y / rootThroughput.y : 0.0,
            rootThroughput.z > 0.0
                    ? expectedAccumulated.z / rootThroughput.z : 0.0);
    float3 expectedSuffix = float3(
            rootThroughput.x > 0.0
                    ? (rootThroughput.x * localRadiance.x)
                            / rootThroughput.x : 0.0,
            rootThroughput.y > 0.0
                    ? (rootThroughput.y * localRadiance.y)
                            / rootThroughput.y : 0.0,
            rootThroughput.z > 0.0
                    ? (rootThroughput.z * localRadiance.z)
                            / rootThroughput.z : 0.0);

    uint mask = primeTestClassify(accumulated)
            | primeTestClassify(ratio)
            | primeTestClassify(suffix)
            | primeTestClassify(rootDirection);
    uint anchorControl = primeSharcTrainingWithAnchorCount(
            PRIME_SHARC_TRAINING_ACTIVE, anchorIndex);
    uint anchorSlot = primeSharcTrainingAnchorSlot(anchorIndex);
    uint pendingControl = primeSharcTrainingWithPendingAnchor(
            anchorControl, anchorSlot);
    if (bypass != expectedBypass
            || primeSharcTerminationReady(1.0)
            || !primeSharcTerminationReady(2.0)
            || selectedPhaseCount != 1u
            || !primeSharcTrainingPixelInPhase(
                    trainingPixel, expectedPhase)
            || any(primeSharcTrainingCoordinate(trainingPixel)
                    != trainingPixel / PRIME_SHARC_TRAINING_GRID_SIZE)
            || primeSharcTrainingAnchorCount(anchorControl) != anchorIndex
            || primeSharcTrainingStoredAnchorCount(anchorControl)
                    != min(anchorIndex, PRIME_SHARC_TRAINING_ANCHOR_CAPACITY)
            || anchorSlot != min(
                    anchorIndex, PRIME_SHARC_TRAINING_ANCHOR_CAPACITY - 1u)
            || primeSharcTrainingCanAppendAnchor(anchorControl)
                    != (anchorIndex < PRIME_SHARC_TRAINING_ANCHOR_CAPACITY)
            || !primeSharcTrainingHasPendingDirection(pendingControl)
            || primeSharcTrainingPendingAnchor(pendingControl) != anchorSlot
            || primeSharcTrainingHasPendingDirection(
                    primeSharcTrainingWithoutPendingAnchor(pendingControl))
            || (initialized
                    && (primeSharcTrainingRootBounce(control)
                            != min(rootBounce, 255u)
                        || !primeSharcTrainingActive(control)))) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }
    if (!primeTestClose(accumulated, expectedAccumulated, 0.0, 0.0)
            || !primeTestClose(ratio, expectedRatio, 0.0, 0.0)
            || !primeTestClose(suffix, expectedSuffix, 1.0e-5, 1.0e-5)
            || !primeTestClose(
                    rootDirection, expectedRootDirection, 0.0, 0.0)) {
        mask |= PRIME_TEST_FAILURE_IDENTITY;
    }

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(mask, control, bypass ? 1u : 0u, 0u);
    if (outputWords >= PRIME_SHARC_TRAINING_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1u] = asuint(float4(accumulated, 0.0));
        primeTestOutput[outputOffset + 2u] = asuint(float4(ratio, 0.0));
        primeTestOutput[outputOffset + 3u] = asuint(float4(rootDirection, 0.0));
    }
}
