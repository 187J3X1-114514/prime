#include "realtime/realtime_wavefront.slang"
#include "realtime/sharc_query_policy.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeWavefrontArea(PrimeSharcIndirectReuse(), context, launchIndex);
}
