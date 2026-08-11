#include "realtime/realtime_wavefront_tail.slang"
#include "realtime/sharc_query_policy.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 1u;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(sourceQueue);
    }
    primeWavefrontTail(
            PrimeSharcIndirectReuse(), context, launchIndex, sourceQueue);
}
