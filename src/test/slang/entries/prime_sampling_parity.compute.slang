#include "core/prime_test_abi.slang"
import "math/sampling.slang";

static const uint PRIME_SAMPLING_INPUT_WORDS = 2;
static const uint PRIME_SAMPLING_OUTPUT_WORDS = 3;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_SAMPLING_INPUT_WORDS);
    uint4 first = primeTestInput[inputOffset];
    uint4 second = primeTestInput[inputOffset + 1];

    PrimeSampleBase sampleBase;
    sampleBase.pixel = first.xy;
    sampleBase.sampleIndex = first.z;
    sampleBase.sampleEpoch = first.w;
    sampleBase.vertexIndex = second.x;
    sampleBase.pathIndex = second.y;
    uint effect = second.z;
    uint dimension = second.w;
    PrimePreparedSampleBase prepared = primePrepareSampleBase(sampleBase);

    uint outputOffset = primeTestOutputOffset(caseIndex, PRIME_SAMPLING_OUTPUT_WORDS);
    primeTestOutput[outputOffset] = uint4(
            primeHash32(first.z),
            primeHighQualityHash(first.w),
            prepared.seed,
            primeEffectSeed(prepared, effect));
    float2 sample2D = primeSobolSample2D(prepared, effect, dimension);
    primeTestOutput[outputOffset + 1] = asuint(float4(
            primeSobolSample1D(prepared, effect, dimension),
            sample2D,
            primeHashSample1D(prepared, effect, dimension)));
    primeTestOutput[outputOffset + 2] = asuint(
            primeSobolSample4D(prepared, effect, dimension));
}
