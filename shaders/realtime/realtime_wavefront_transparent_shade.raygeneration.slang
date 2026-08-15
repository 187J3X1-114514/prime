#include "realtime/realtime_wavefront_transparent_shade.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 1u;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_TRANSPARENT_SHADE_QUEUE);
    }
    primeWavefrontTransparentShade(context, launchIndex, sourceQueue);
}
