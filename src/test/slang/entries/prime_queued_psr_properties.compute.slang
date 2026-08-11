#include "core/prime_test_abi.slang"
import "core/queued_psr_math.slang";

static const uint PRIME_PSR_INPUT_WORDS = 21;
static const uint PRIME_PSR_WITNESS_WORDS = 6;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_PSR_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    uint count = min(control.x, PRIME_QUEUED_PSR_CAPACITY);
    uint reflectionMask = control.y;
    bool forceOverflow = control.z != 0;
    float3 cameraPosition = asfloat(primeTestInput[inputOffset + 1]).xyz;
    float3 target = asfloat(primeTestInput[inputOffset + 2]).xyz;
    float3 targetNormal = normalize(asfloat(primeTestInput[inputOffset + 3]).xyz);
    float3 direction = normalize(asfloat(primeTestInput[inputOffset + 4]).xyz);

    float3 positions[8];
    float3 normals[8];
    PrimeQueuedPsrState state = primeEmptyQueuedPsrState();
    float3 previousPosition = cameraPosition;
    for (uint index = 0; index < count; ++index) {
        positions[index] = asfloat(primeTestInput[inputOffset + 5 + index]).xyz;
        normals[index] = normalize(asfloat(
                primeTestInput[inputOffset + 5 + PRIME_QUEUED_PSR_CAPACITY + index]).xyz);
        state = primeAppendQueuedPsrState(
                state,
                cameraPosition,
                previousPosition,
                positions[index],
                normals[index],
                (reflectionMask & (1 << index)) != 0);
        previousPosition = positions[index];
    }
    if (forceOverflow) {
        state = primeAppendQueuedPsrState(
                state,
                cameraPosition,
                previousPosition,
                target,
                targetNormal,
                false);
    }

    float3 expectedDirection = direction;
    float3 expectedNormal = targetNormal;
    for (int index = int(count) - 1; index >= 0; --index) {
        if ((reflectionMask & (1 << uint(index))) != 0) {
            expectedDirection -= 2.0 * normals[index]
                    * dot(expectedDirection, normals[index]);
            expectedNormal -= 2.0 * normals[index]
                    * dot(expectedNormal, normals[index]);
        }
    }
    float3 expectedPosition = float3(0.0);
    if (count > 0) {
        float expectedLength = length(positions[0] - cameraPosition);
        for (uint index = 1; index < count; ++index) {
            expectedLength += length(positions[index] - positions[index - 1]);
        }
        expectedLength += length(target - positions[count - 1]);
        expectedPosition = normalize(positions[0] - cameraPosition) * expectedLength;
    }

    float3 virtualPosition = float3(0.0);
    float3 virtualNormal = float3(0.0);
    bool built = primeBuildQueuedPsrGuideValue(
            state,
            previousPosition,
            target,
            targetNormal,
            virtualPosition,
            virtualNormal);
    if (!built) {
        virtualPosition = float3(0.0);
        virtualNormal = float3(0.0);
    }
    float3 virtualDirection = primeQueuedPsrVirtualDirection(state, direction);
    float anchor = primeQueuedPsrAnchorDistanceValue(
            state, cameraPosition, target, targetNormal);
    float3 targetDirection = target - cameraPosition;
    float targetLengthSquared = dot(targetDirection, targetDirection);
    float3 primaryDirection = count == 0 && targetLengthSquared > 1.0e-12
            ? targetDirection * rsqrt(targetLengthSquared)
            : count > 0
                    ? normalize(positions[0] - cameraPosition)
                    : float3(0.0);
    float denominator = dot(primaryDirection, targetNormal);
    float expectedAnchor = !forceOverflow && abs(denominator) > 1.0e-4
            ? dot(target - cameraPosition, targetNormal) / denominator
            : -1.0;
    if (!(expectedAnchor > 0.0)) expectedAnchor = -1.0;

    uint mask = 0;
    bool expectedBuilt = count > 0 && !forceOverflow;
    if (built != expectedBuilt
            || primeQueuedPsrCount(state) != count
            || primeQueuedPsrOverflowed(state) != forceOverflow) {
        mask |= PRIME_TEST_FAILURE_EVENT;
    }
    if (!primeTestClose(virtualDirection, expectedDirection, 2.0e-6, 2.0e-5)
            || (expectedBuilt && !primeTestClose(
                    virtualNormal, expectedNormal, 2.0e-6, 2.0e-5))
            || (expectedBuilt && !primeTestClose(
                    virtualPosition, expectedPosition, 2.0e-5, 2.0e-5))
            || !primeTestClose(anchor, expectedAnchor, 2.0e-5, 2.0e-5)) {
        mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    mask |= primeTestClassify(virtualDirection)
            | primeTestClassify(virtualNormal)
            | primeTestClassify(virtualPosition)
            | primeTestClassify(anchor);

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(mask, count, reflectionMask, caseIndex);
    if (outputWords >= PRIME_PSR_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1] = asuint(float4(virtualDirection, anchor));
        primeTestOutput[outputOffset + 2] = asuint(float4(expectedDirection, expectedAnchor));
        primeTestOutput[outputOffset + 3] = asuint(float4(virtualPosition, 0.0));
        primeTestOutput[outputOffset + 4] = asuint(float4(expectedPosition, 0.0));
        primeTestOutput[outputOffset + 5] = asuint(float4(virtualNormal, 0.0));
    }
}
