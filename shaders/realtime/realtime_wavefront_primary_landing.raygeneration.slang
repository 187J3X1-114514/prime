#include "realtime/realtime_wavefront_primary_landing_classify.slang"

[shader("raygeneration")]
void main()
{
    uint launchIndex = DispatchRaysIndex().x;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_PRIMARY_QUEUE);
    }
    primeWavefrontPrimaryLandingClassify(launchIndex);
}
