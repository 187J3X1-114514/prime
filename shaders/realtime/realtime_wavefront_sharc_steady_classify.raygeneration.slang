#include "realtime/realtime_wavefront_steady.slang"
#include "realtime/sharc_query_policy.slang"
#include "realtime/sharc_training_policy.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0);
    }
    primeWavefrontSharcSteadyClassify(
            PrimeSharcIndirectReuse(),
            PrimeIntegratedSharcTraining(),
            context,
            launchIndex);
}
