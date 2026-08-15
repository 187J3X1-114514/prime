#include "realtime/realtime_wavefront_head.slang"

[shader("raygeneration")]
void main()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeWavefrontHead(context, launchIndex);
}
