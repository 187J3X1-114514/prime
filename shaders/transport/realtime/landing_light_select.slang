#language slang 2026
module "transport/realtime/landing_light_select.slang";

import "prime_abi_types.slang";
import "prime_realtime_abi.slang";
import "contract/light_sample.slang";
import "contract/path.slang";
import "math/sampling.slang";
import "policy/runtime/config.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/light/tree_select.slang";
import "state/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/staged_light.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "transport/direct/selection.slang";
import "bsdf/compact/contract/volume.slang";

public void primeSelectLandingLight(uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_PRIMARY_QUEUE, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    PrimeStagedLightSelection selection = primeDisabledStagedLightSelection();
    if (surface.hitKind == PRIME_HIT_SURFACE) {
        PathState path = primeWavefrontPath(
                primeWavefrontPixel(recordIndex), record);
        PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
        float3 viewDirection = -path.rayDirection;
        PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
                surface, viewDirection, volumeStack, primeUsesAirGap());
        if (primeClosureSupports(
                closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
            uint control = asuint(record.rayDirectionAndDenoiserControl.w);
            bool guideDual = primeWavefrontGuideEnabled(control)
                    && (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u;
            PrimePreparedSampleBase preparedSample = primePreparePathSample(
                    path.sampleSeed,
                    primeSampleIndex(),
                    primePathBounce(path.control) + 1u);
            float treeSample = guideDual
                    ? primeSobolSample2D(
                            preparedSample,
                            PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                            PRIME_SAMPLE_DIMENSION_PRIMARY).x
                    : primeSobolSample3D(
                            preparedSample,
                            PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                            PRIME_SAMPLE_DIMENSION_PRIMARY).x;
            selection.area = primeSelectAreaLight(
                    surface.position,
                    primeAreaLightReceiverNormal(surface, viewDirection),
                    treeSample);
        }
    }
    primeStoreStagedLightSelection(recordIndex, selection);
}
