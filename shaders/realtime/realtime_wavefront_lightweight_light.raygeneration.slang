#include "realtime/realtime_wavefront_light.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_AREA_QUEUE);
    }
    primeWavefrontLight(
            PrimeSunOnlyWavefrontLightNee(),
            PrimeNoIndirectReuse(),
            PrimeNoWavefrontSharcTraining(),
            context,
            launchIndex);
}
