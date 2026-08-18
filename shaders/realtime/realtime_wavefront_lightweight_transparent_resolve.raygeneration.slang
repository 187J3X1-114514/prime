#include "realtime/realtime_wavefront_lightweight_transparent_resolve.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE);
    }
    primeWavefrontLightweightTransparentResolve(context, launchIndex);
}
