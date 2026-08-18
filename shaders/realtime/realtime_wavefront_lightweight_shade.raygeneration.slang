#include "realtime/realtime_wavefront_shade.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 1u;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_SHADE_QUEUE);
    }
    primeWavefrontShade(
            PrimeNoSecondaryAreaNee(),
            PrimeNoIndirectReuse(),
            PrimeNoWavefrontSharcTraining(),
            context,
            launchIndex,
            sourceQueue);
}
