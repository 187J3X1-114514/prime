#include "realtime/realtime_wavefront_primary.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_PRIMARY_QUEUE);
    }
    primeWavefrontPrimary(
            PrimeStagedSecondaryAreaNee(),
            context,
            launchIndex);
}
