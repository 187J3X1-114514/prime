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
    if (launchIndex.x == 0u) {
        primeResetWavefrontQueue(sourceQueue);
    }
    primeWavefrontStep(context, launchIndex.x, sourceQueue);
}

void primeRealtimeWavefrontPrimaryEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex.x == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_SHADE_QUEUE);
    }
    primeWavefrontPrimary(context, launchIndex.x);
}

void primeRealtimeWavefrontPrimaryAreaEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeWavefrontPrimaryArea(context, launchIndex.x);
}

void primeRealtimeWavefrontPrimarySunEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex.x == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_AREA_QUEUE);
    }
    primeWavefrontPrimarySun(context, launchIndex.x);
}

void primeRealtimeWavefrontShadeEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 1u;
    if (launchIndex.x == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_SHADE_QUEUE);
    }
    primeWavefrontShade(
            PrimeNoIndirectReuse(), context, launchIndex.x, sourceQueue);
}

void primeRealtimeWavefrontTransparentShadeEntry()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    uint sourceQueue = (primeWavefrontShaderRecord.control >> 8u) & 1u;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_TRANSPARENT_SHADE_QUEUE);
    }
    primeWavefrontTransparentShade(context, launchIndex, sourceQueue);
}

void primeRealtimeWavefrontAreaEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex.x == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_AREA_QUEUE);
    }
    primeWavefrontArea(PrimeNoIndirectReuse(), context, launchIndex.x);
}

void primeRealtimeWavefrontSunEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex.x == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_SUN_QUEUE);
    }
    primeWavefrontSun(context, launchIndex.x);
}

void primeRealtimeWavefrontResolveEntry()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeWavefrontResolve(context, launchIndex);
}

void primeRealtimeWavefrontTransparentResolveEntry()
{
    uint launchIndex = DispatchRaysIndex().x;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE);
    }
    primeWavefrontTransparentResolve(context, launchIndex);
}
