#include "offline/offline_wavefront.slang"

[shader("raygeneration")]
void main()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
#if defined(PRIME_OFFLINE_ENTRY_HEAD)
    primeOfflineWavefrontHead(context, launchIndex);
#elif defined(PRIME_OFFLINE_ENTRY_STEP)
    uint sourceQueue = (primeOfflineShaderRecord.control >> 8u) & 1u;
    primeOfflineWavefrontStep(context, launchIndex.x, sourceQueue);
#elif defined(PRIME_OFFLINE_ENTRY_AREA)
    uint sourceQueue = (primeOfflineShaderRecord.control >> 8u) & 1u;
    primeOfflineWavefrontArea(context, launchIndex.x, sourceQueue);
#elif defined(PRIME_OFFLINE_ENTRY_TAIL)
    uint sourceQueue = (primeOfflineShaderRecord.control >> 8u) & 1u;
    primeOfflineWavefrontTail(context, launchIndex.x, sourceQueue);
#elif defined(PRIME_OFFLINE_ENTRY_RESOLVE)
    primeOfflineWavefrontResolve(launchIndex);
#else
#error An offline wavefront execution-mode entry must be selected.
#endif
}
