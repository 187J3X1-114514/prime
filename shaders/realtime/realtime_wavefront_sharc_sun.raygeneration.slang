#include "realtime/realtime_wavefront.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_SUN_QUEUE);
    }
    primeWavefrontSun(context, launchIndex);
}
