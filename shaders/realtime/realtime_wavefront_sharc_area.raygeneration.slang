#include "realtime/realtime_wavefront.slang"
#include "realtime/sharc_query_policy.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_AREA_QUEUE);
    }
    primeWavefrontArea(PrimeSharcIndirectReuse(), context, launchIndex);
}
