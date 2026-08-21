#include "realtime/realtime_wavefront_bounce.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 0xffu;
    // Indirect width has already been fetched. Retaining the counter through the first stage lets
    // this shader reuse the exact source domain, then safely returns the queue to its owner.
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(sourceQueue);
    }
    primeWavefrontTransparentArea(
            context,
            launchIndex,
            sourceQueue);
}
