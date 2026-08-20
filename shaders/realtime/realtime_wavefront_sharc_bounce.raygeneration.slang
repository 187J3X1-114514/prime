#include "realtime/realtime_wavefront_bounce.slang"
#include "realtime/sharc_query_policy.slang"
#include "realtime/sharc_training_policy.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 0xffu;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(sourceQueue);
    }
    primeWavefrontOrdinaryBounce(
            PrimeSharcIndirectReuse(),
            PrimeIntegratedSharcTraining(),
            context,
            launchIndex,
            sourceQueue);
}
