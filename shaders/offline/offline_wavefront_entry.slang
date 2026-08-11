void primeOfflineWavefrontHeadEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeOfflineWavefrontHead(context, launchIndex);
}

void primeOfflineWavefrontStepEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeOfflineShaderRecord.control >> 8u) & 1u;
    primeOfflineWavefrontStep(context, launchIndex.x, sourceQueue);
}

void primeOfflineWavefrontAreaEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeOfflineShaderRecord.control >> 8u) & 1u;
    primeOfflineWavefrontArea(context, launchIndex.x, sourceQueue);
}

void primeOfflineWavefrontTailEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeOfflineShaderRecord.control >> 8u) & 1u;
    primeOfflineWavefrontTail(context, launchIndex.x, sourceQueue);
}

void primeOfflineWavefrontResolveEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    primeOfflineWavefrontResolve(launchIndex);
}
