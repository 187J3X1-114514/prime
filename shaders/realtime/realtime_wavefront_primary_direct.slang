#include "realtime/realtime_wavefront_common.slang"

void primeWavefrontPrimaryDirect(
        inout PrimeIntegratorContext context,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    primeRestoreWavefrontDiagnostic(record, context.diagnostics);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
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
    bool transparent = primeMaterialIsTransmissive(surface.materialControl);

    PrimeDirectLightingSplit area = primeEstimatePrimaryNonsunDirect(
            context,
            surface,
            viewDirection,
            preparedSample,
            volumeStack,
            transparent);
    area.diffuse *= path.throughput;
    area.specular *= path.throughput;
    primeAccumulatePrimaryDirectSplit(context, pixel, area);

    PrimeDenoiserGuides guides = primeEmptyDenoiserGuides();
    guides.primaryAreaDiffuse = area.diffuse;
    guides.primaryAreaSpecular = area.specular;
    if (any(area.diffuse > float3(0.0))
            || any(area.specular > float3(0.0))) {
        guides.primaryAreaDirection = area.direction;
    }
    primeStoreWavefrontArea(pixel, guides);

    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
            context,
            integrator,
            surface,
            viewDirection,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            volumeStack,
            transparent);
    PrimeDirectLightingSplit direct = sun.lighting;
    direct.diffuse *= path.throughput;
    direct.specular *= path.throughput;
    if (transparent) {
        direct.diffuse *= sun.visibility;
        direct.specular *= sun.visibility;
        primeAccumulatePrimaryDirectSplit(context, pixel, direct);
    } else {
        primeStoreWavefrontPrimarySun(
                pixel,
                direct.diffuse + direct.specular,
                sun.visibility,
                sun.penumbra);
    }
    primeStoreWavefrontDiagnostic(recordIndex, context.diagnostics);
}
