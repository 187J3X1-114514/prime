#include "realtime/realtime_megakernel.slang"
#include "realtime/sharc_query_policy.slang"

[shader("raygeneration")]
void main() {
    uint2 pixel = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    PrimeIntegrationResult result = primeMegakernelIntegrate(
            PrimeSharcIndirectReuse(), context, pixel, cameraSample);
    primeMegakernelWrite(context, pixel, cameraSample, result);
}
