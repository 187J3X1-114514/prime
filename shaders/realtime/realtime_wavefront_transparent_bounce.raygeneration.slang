#include "realtime/realtime_wavefront_bounce.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 0xffu;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(sourceQueue);
    }
    primeWavefrontTransparentBounce(
            context,
            launchIndex,
            sourceQueue);
}
