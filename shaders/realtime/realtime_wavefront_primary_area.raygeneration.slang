#include "realtime/realtime_wavefront_primary_area.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeWavefrontPrimaryArea(context, launchIndex);
}
