#language slang 2026
module "transport/realtime/fixed_light_select.slang";

import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "contract/path.slang";
import "math/sampling.slang";
import "policy/runtime/config.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/light/tree_select.slang";
import "state/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/fixed_light.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "transport/direct/selection.slang";
import "bsdf/compact/contract/volume.slang";

public void primeSelectFixedLight(uint sourceQueue, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    PrimeFixedLightSelection selection = primeDisabledFixedLightSelection();
    if (surface.hitKind == PRIME_HIT_SURFACE) {
        PathState path = primeWavefrontPath(
                primeWavefrontPixel(recordIndex), record);
        PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
        float3 viewDirection = -path.rayDirection;
        PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
                surface, viewDirection, volumeStack, primeUsesAirGap());
        if (primeClosureSupports(
                closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
            selection.area = primeInvalidAreaLightSelection();
            PrimePreparedSampleBase preparedSample = primePreparePathSample(
                    path.sampleSeed,
                    primeSampleIndex(),
                    primePathBounce(path.control) + 1u);
            selection.area = primeSelectAreaLight(
                    surface.position,
                    primeAreaLightReceiverNormal(surface, viewDirection),
                    primeSobolSample3D(
                            preparedSample,
                            PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                            PRIME_SAMPLE_DIMENSION_PRIMARY).x);
        }
    }
    primeStoreFixedLightSelection(recordIndex, selection);
}
