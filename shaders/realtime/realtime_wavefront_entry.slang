void primeRealtimeWavefrontHeadEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeWavefrontHead(context, launchIndex);
}

void primeRealtimeWavefrontStepEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 1u;
    primeWavefrontStep(context, launchIndex.x, sourceQueue);
}

void primeRealtimeWavefrontShadeEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 1u;
    primeWavefrontShade(
            PrimeNoIndirectReuse(), context, launchIndex.x, sourceQueue);
}

void primeRealtimeWavefrontAreaEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeWavefrontArea(PrimeNoIndirectReuse(), context, launchIndex.x);
}

void primeRealtimeWavefrontResolveEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeWavefrontResolve(context, launchIndex);
}
