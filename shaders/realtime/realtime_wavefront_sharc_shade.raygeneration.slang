#include "realtime/realtime_wavefront.slang"
#include "realtime/sharc_query_policy.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 1u;
    primeWavefrontShade(
            PrimeSharcIndirectReuse(), context, launchIndex, sourceQueue);
}
