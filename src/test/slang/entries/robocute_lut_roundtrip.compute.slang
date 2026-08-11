
#define PRIME_RC_TRANSMISSION_GGX_SET 0
#define PRIME_RC_TRANSMISSION_GGX_BINDING 2
#include "core/prime_test_abi.slang"
#include "bsdf/core/robocute_microfacet.slang"

static const uint3 PRIME_TEST_GGX_SIZE = uint3(44u, 32u, 159u);

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) {
        return;
    }
    uint x = caseIndex % PRIME_TEST_GGX_SIZE.x;
    uint yz = caseIndex / PRIME_TEST_GGX_SIZE.x;
    uint y = yz % PRIME_TEST_GGX_SIZE.y;
    uint z = yz / PRIME_TEST_GGX_SIZE.y;
    primeTestOutput[caseIndex] = asuint(
            primeRcTransmissionGgxEnergy.Load(int4(x, y, z, 0)));
}
