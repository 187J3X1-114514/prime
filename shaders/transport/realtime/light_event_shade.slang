#language slang 2026
module "transport/realtime/light_event_shade.slang";

import "transport/realtime/light_event_classify.slang";
import "contract/realtime/light_event.slang";

import "policy/realtime/sharc.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "math/transport.slang";
import "service/light/sample.slang";
import "model/material/defaults.slang";
import "service/trace/context.slang";
import "state/realtime/light.slang";
import "state/realtime/contract.slang";
import "state/realtime/ordinary.slang";
import "contract/light.slang";
import "transport/direct/primary.slang";
import "transport/direct/selection.slang";
import "transport/surface/medium.slang";
import "bsdf/compact/contract/volume.slang";
import "policy/transport/light_family.slang";

public bool primePrepareRealtimeAreaSample(
        inout PrimeIntegratorContext context,
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        SurfaceInteraction surface,
        PrimeRealtimeLightEvent event,
        out float3 preparedThroughput,
        inout PrimeRealtimeAreaSampleInput lightData) {
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        preparedThroughput = path.throughput;
        return false;
    }
    preparedThroughput = path.throughput;
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    lightData.position = surface.position;
    lightData.receiverNormal =
            primeAreaLightReceiverNormal(surface, -path.rayDirection);
    lightData.treeSample =
            event.type == PRIME_REALTIME_LIGHT_EVENT_GUIDE_DUAL
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
    return true;
}

public AreaLightSample primeExecuteRealtimeAreaSample(
        inout PrimeIntegratorContext context,
        PrimeRealtimeAreaSampleInput lightData) {
    AreaLightSample area = primeSampleAreaLight(
            lightData.position,
            lightData.receiverNormal,
            lightData.treeSample,
            lightData.positionSample);
    return area;
}

public PrimeRealtimeLightResult primeShadeRealtimeSunLightEvent<SharcTraining>(
        SharcTraining sharcTraining,
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        PrimeRealtimeLightEvent event)
    where SharcTraining : IPrimeWavefrontSharcTraining
{
    PrimeRealtimeLightResult result;
    result.storedSunProbability = event.storedSunProbability;
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return result;
    }
    float3 viewDirection = -path.rayDirection;
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
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
    return result;
}

public PrimeRealtimeLightResult primeShadeRealtimeAreaLightEvent<SharcTraining>(
        SharcTraining sharcTraining,
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        PrimeRealtimeLightEvent event,
        bool preparedArea,
        float3 preparedThroughput,
        AreaLightSample area)
    where SharcTraining : IPrimeWavefrontSharcTraining
{
    PrimeRealtimeLightResult result;
    result.storedSunProbability = event.storedSunProbability;
    if (!preparedArea) {
        return result;
    }

    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    path.throughput = preparedThroughput;
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    float3 viewDirection = -path.rayDirection;
    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);

    if (event.type == PRIME_REALTIME_LIGHT_EVENT_GUIDE_DUAL) {
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
    return result;
}
