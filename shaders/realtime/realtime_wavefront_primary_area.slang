#include "realtime/realtime_wavefront_common.slang"

void primeWavefrontPrimaryArea(
        inout PrimeIntegratorContext context,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(
            recordIndex,
            record.traceOriginAndPathControl.xyz,
            record.rayDirectionAndDenoiserControl.xyz);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return;
    }
    float3 viewDirection = -path.rayDirection;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    if (!primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
        return;
    }

    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    PrimeDirectLightingSplit direct = primeEstimatePrimaryNonsunDirect(
            context,
            surface,
            viewDirection,
            preparedSample,
            volumeStack,
            primeMaterialIsTransmissive(surface.materialControl));
    direct.diffuse *= path.throughput;
    direct.specular *= path.throughput;
    primeAccumulatePrimaryDirectSplit(context, pixel, direct);

    PrimeDenoiserGuides guides = primeEmptyDenoiserGuides();
    guides.primaryAreaDiffuse = direct.diffuse;
    guides.primaryAreaSpecular = direct.specular;
    if (any(direct.diffuse > float3(0.0))
            || any(direct.specular > float3(0.0))) {
        guides.primaryAreaDirection = direct.direction;
    }
    primeStoreWavefrontArea(pixel, guides);
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
}
