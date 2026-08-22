#include "realtime/realtime_wavefront_light.slang"
#include "realtime/transparent_light_event.slang"
#if !defined(PRIME_WAVEFRONT_CLASSIFY_ONLY) \
        && !defined(PRIME_WAVEFRONT_NO_ADVANCE)
#include "realtime/realtime_wavefront_advance.slang"
#endif

struct PrimeFusedWavefrontLightResult {
    uint storedSunProbability;
    bool diagnosticsCommitted;
};

struct PrimeTransparentLightEvent {
    uint type;
    uint storedSunProbability;
};

PrimeTransparentLightEvent primeClassifyTransparentLightEvent(
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control) {
    PrimeTransparentLightEvent event;
    event.type = PRIME_TRANSPARENT_LIGHT_EVENT_NONE;
    event.storedSunProbability = 0u;
    if (surface.hitKind != PRIME_HIT_SURFACE) {
        return event;
    }

    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    float3 viewDirection = -path.rayDirection;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack);
    if (!primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
        return event;
    }

    bool guideEnabled = primeWavefrontGuideEnabled(control);
    bool hasGuide = (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    if (guideEnabled && !hasGuide) {
        if (!primeClosureIsDiscreteOnly(closureTraits)) {
            event.type = PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY;
        }
        return event;
    }

    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    float sunProbability = primeMixedWavefrontSunProbability(
            integrator, surface, viewDirection);
    event.storedSunProbability =
            primeStoredSunFamilyProbability(sunProbability);
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    event.type = primeSecondarySamplesSunLight(
            preparedSample, sunProbability)
            ? PRIME_TRANSPARENT_LIGHT_EVENT_SUN
            : PRIME_TRANSPARENT_LIGHT_EVENT_AREA;
    return event;
}

bool primeTransparentLightEventSamplesArea(PrimeTransparentLightEvent event) {
    return event.type == PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY
            || event.type == PRIME_TRANSPARENT_LIGHT_EVENT_AREA;
}

#if !defined(PRIME_WAVEFRONT_CLASSIFY_ONLY)
bool primePrepareTransparentAreaSample(
        inout PrimeIntegratorContext context,
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        SurfaceInteraction surface,
        PrimeTransparentLightEvent event,
        out float3 preparedThroughput,
        inout PrimeTransparentAreaSampleInput lightData) {
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        preparedThroughput = path.throughput;
        return false;
    }
    preparedThroughput = path.throughput;
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    lightData.position = surface.position;
    lightData.receiverNormal =
            primeAreaLightReceiverNormal(surface, -path.rayDirection);
    lightData.treeSample =
            event.type == PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY
            ? primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY)
            : primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY).xy;
    lightData.positionSample = primeSobolSample2D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
            PRIME_SAMPLE_DIMENSION_SECONDARY);
    lightData.numericalFlags = context.diagnostics.flags;
    lightData.numericalContext =
            context.diagnostics.firstContext;
    return true;
}

AreaLightSample primeExecuteTransparentAreaSample(
        inout PrimeIntegratorContext context,
        PrimeTransparentAreaSampleInput lightData) {
    PrimeNumericalDiagnostics diagnostics;
    diagnostics.flags = lightData.numericalFlags;
    diagnostics.firstContext =
            lightData.numericalContext;
    AreaLightSample area = primeSampleAreaLight(
            diagnostics,
            lightData.position,
            lightData.receiverNormal,
            lightData.treeSample,
            lightData.positionSample);
    context.diagnostics = diagnostics;
    return area;
}

PrimeFusedWavefrontLightResult primeShadeTransparentSunLightEvent<SharcTraining>(
        SharcTraining sharcTraining,
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        PrimeTransparentLightEvent event)
    where SharcTraining : IPrimeWavefrontSharcTraining
{
    PrimeFusedWavefrontLightResult result;
    result.storedSunProbability = event.storedSunProbability;
    result.diagnosticsCommitted = false;
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return result;
    }
    float3 viewDirection = -path.rayDirection;
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    float sunProbability = primeSunFamilyProbabilityFromStored(
            event.storedSunProbability);
    float3 direct = primeEstimateDirectSun(
            context,
            integrator,
            surface,
            viewDirection,
            primeSobolSample2D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            volumeStack,
            sunProbability);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    if (!primeWavefrontTransparentBranch(control)) {
        sharcTraining.recordHit(context, path, surface, direct);
    }
    float3 contribution = path.throughput * direct;
    if (any(contribution > float3(0.0))) {
        primeAccumulateDeferredDirectLight(
                context,
                pixel,
                control,
                contribution);
    }
    result.diagnosticsCommitted = true;
    return result;
}

PrimeFusedWavefrontLightResult primeShadeTransparentAreaLightEvent<SharcTraining>(
        SharcTraining sharcTraining,
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        PrimeTransparentLightEvent event,
        bool preparedArea,
        float3 preparedThroughput,
        AreaLightSample area)
    where SharcTraining : IPrimeWavefrontSharcTraining
{
    PrimeFusedWavefrontLightResult result;
    result.storedSunProbability = event.storedSunProbability;
    result.diagnosticsCommitted = false;
    if (!preparedArea) {
        result.diagnosticsCommitted =
                event.type == PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY;
        return result;
    }

    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    path.throughput = preparedThroughput;
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    float3 viewDirection = -path.rayDirection;
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    primeSetNumericalContext(
            context.diagnostics, PRIME_NUMERICAL_STAGE_DIRECT_LIGHT, path.bounce);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);

    if (event.type == PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY) {
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
                false);
        PrimeDirectLightingSplit nonsun =
                primeEstimatePrimaryDirectPreparedAreaLight(
                        context,
                        surface,
                        viewDirection,
                        area,
                        volumeStack,
                        false);
        primeAccumulateTransparentPrimaryDirect(
                context,
                pixel,
                primeWavefrontTransmissionBranch(
                        asuint(record.rayDirectionAndDenoiserControl.w)),
                path.throughput
                        * (sun.lighting.diffuse * sun.visibility
                                + nonsun.diffuse),
                path.throughput
                        * (sun.lighting.specular * sun.visibility
                                + nonsun.specular));
        result.storedSunProbability = 0u;
        result.diagnosticsCommitted = true;
        return result;
    }

    float sunProbability = primeSunFamilyProbabilityFromStored(
            event.storedSunProbability);
    float3 direct = primeEstimateDirectPreparedAreaLight(
            context,
            surface,
            viewDirection,
            area,
            volumeStack,
            1.0 - sunProbability);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    if (!primeWavefrontTransparentBranch(control)) {
        sharcTraining.recordHit(context, path, surface, direct);
    }
    float3 contribution = path.throughput * direct;
    if (any(contribution > float3(0.0))) {
        primeAccumulateDeferredDirectLight(
                context,
                pixel,
                control,
                contribution);
    }
    result.diagnosticsCommitted = true;
    return result;
}
#endif
