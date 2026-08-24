#language slang 2026
module "transport/realtime/landing_guide_advance.slang";

import "policy/realtime/landing.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";

import "transport/realtime/advance.slang";
import "transport/realtime/landing_publish.slang";

public void primeAdvanceLandingGuidePath(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    uint storedSunProbability =
            primeLoadWavefrontSunFamilyProbability(recordIndex);
    bool continuation = primeAdvanceTransparentWavefrontRecord(
            PrimeLandingTransportPhase(),
            context,
            recordIndex,
            record,
            surface,
            control,
            storedSunProbability,
            true);
    primePublishLandingContinuation(recordIndex, true, continuation);
}
