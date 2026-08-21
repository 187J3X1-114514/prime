#include "realtime/realtime_wavefront_bounce.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 0xffu;
    // Indirect width is already fetched; clear only the compact area counter before reuse.
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_AREA_QUEUE);
    }
    primeWavefrontTransparentArea(
            context,
            launchIndex,
            sourceQueue);
}
