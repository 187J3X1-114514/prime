#include "realtime/realtime_wavefront.slang"

[shader("raygeneration")]
void main()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
#if defined(PRIME_REALTIME_ENTRY_HEAD)
    primeWavefrontHead(context, launchIndex);
#elif defined(PRIME_REALTIME_ENTRY_STEP)
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 1u;
    primeWavefrontStep(context, launchIndex.x, sourceQueue);
#elif defined(PRIME_REALTIME_ENTRY_SHADE)
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 1u;
    primeWavefrontShade(context, launchIndex.x, sourceQueue);
#elif defined(PRIME_REALTIME_ENTRY_AREA)
    primeWavefrontArea(context, launchIndex.x);
#elif defined(PRIME_REALTIME_ENTRY_RESOLVE)
    primeWavefrontResolve(context, launchIndex);
#else
#error A realtime wavefront execution-mode entry must be selected.
#endif
}
