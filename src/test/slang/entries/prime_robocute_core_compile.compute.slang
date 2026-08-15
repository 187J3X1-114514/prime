void primeRecordNonnegative(float value) {}
void primeRecordNonnegative(float3 value) {}
void primeRecordUnit(float value) {}
void primeRecordUnit(float3 value) {}
void primeRecordDirection(float3 value) {}
bool primeUsesAirGap() { return true; }
import "bsdf/core/robocute_common.slang";
#include "bsdf/adapter/bsdf.slang"
#include "core/prime_test_abi.slang"

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    float f0 = asfloat(primeTestInput[primeTestInputOffset(caseIndex, 1)].x);
    float ior = primeRcF0ToIor(f0);
    uint outputOffset = primeTestOutputOffset(caseIndex, primeTestOutputWords());
    primeTestOutput[outputOffset] = uint4(
            primeTestClassify(ior), asuint(ior), caseIndex, 0);
}
