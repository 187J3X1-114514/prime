#define PRIME_USE_LITE_BSDF 1
#include "realtime/realtime_wavefront_primary.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_SHADE_QUEUE);
    }
    primeWavefrontPrimary(
            PrimeStagedLightweightAreaNee(),
            PrimeRefractedTransparentContinuation(),
            context,
            launchIndex);
}
