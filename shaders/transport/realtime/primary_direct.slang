#language slang 2026
module "transport/realtime/primary_direct.slang";

import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "service/bsdf/contract/sample.slang";
import "contract/realtime/guide.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "model/material/defaults.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/guide.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "contract/light.slang";
import "transport/direct/primary.slang";
import "transport/surface/medium.slang";
import "bsdf/compact/contract/volume.slang";
import "model/realtime/guide.slang";

public void primeWavefrontPrimaryDirect(
        inout PrimeIntegratorContext context,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return;
    }
    float3 viewDirection = -path.rayDirection;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack, primeUsesAirGap());
    if (!primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
        return;
    }

    PrimePreparedSampleBase preparedSample =
            primePrepareSampleBase(primeMakeSampleBase(path, path.bounce + 1u));
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
}
