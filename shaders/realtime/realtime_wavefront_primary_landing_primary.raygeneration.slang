#include "realtime/realtime_wavefront_primary_landing.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_TRACE_QUEUE_0);
    }
    primeWavefrontPrimaryLandingPrimary(context, launchIndex);
}
