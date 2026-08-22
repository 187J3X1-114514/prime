#include "realtime/realtime_wavefront_steady.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0);
    }
    primeWavefrontSteadyClassify(context, launchIndex);
}
