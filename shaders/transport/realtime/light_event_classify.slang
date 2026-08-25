#language slang 2026
module "transport/realtime/light_event_classify.slang";

import "contract/realtime/light_event.slang";

import "prime_abi_types.slang";
import "state/realtime/contract.slang";
import "state/realtime/ordinary.slang";
import "service/bsdf/contract/sample.slang";
import "policy/runtime/config.slang";
import "service/bsdf/dispatch/traits.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";

public struct PrimeRealtimeLightEvent {
    public uint type;
};

public PrimeRealtimeLightEvent primeClassifyRealtimeLightEvent(
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control) {
    PrimeRealtimeLightEvent event;
    event.type = PRIME_REALTIME_LIGHT_EVENT_NONE;
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
            event.type = PRIME_REALTIME_LIGHT_EVENT_GUIDE_DUAL;
        }
        return event;
    }

    event.type = PRIME_REALTIME_LIGHT_EVENT_DUAL;
    return event;
}

public bool primeRealtimeLightEventSamplesArea(PrimeRealtimeLightEvent event) {
    return event.type == PRIME_REALTIME_LIGHT_EVENT_GUIDE_DUAL
            || event.type == PRIME_REALTIME_LIGHT_EVENT_DUAL;
}
