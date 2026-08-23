#language slang 2026
module "transport/realtime/light_event_classify.slang";

import "transport/realtime/light.slang";
import "contract/realtime/light_event.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "state/realtime/contract.slang";
import "state/realtime/ordinary.slang";
import "service/bsdf/contract/sample.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "service/bsdf/dispatch/traits.slang";
import "transport/direct/selection.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";
import "policy/transport/light_family.slang";

public struct PrimeFusedWavefrontLightResult {
    public uint storedSunProbability;
};

public struct PrimeTransparentLightEvent {
    public uint type;
    public uint storedSunProbability;
};

public PrimeTransparentLightEvent primeClassifyTransparentLightEvent(
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
            surface, viewDirection, volumeStack, primeUsesAirGap());
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

public bool primeTransparentLightEventSamplesArea(PrimeTransparentLightEvent event) {
    return event.type == PRIME_TRANSPARENT_LIGHT_EVENT_PRIMARY
            || event.type == PRIME_TRANSPARENT_LIGHT_EVENT_AREA;
}
