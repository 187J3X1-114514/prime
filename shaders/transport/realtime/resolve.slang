#language slang 2026
module "transport/realtime/resolve.slang";

import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "service/camera/projection.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";

import "state/realtime/contract.slang";
import "service/reconstruct/output.slang";

import "contract/realtime/ordinary.slang";
import "contract/realtime/resolved.slang";
import "service/reconstruct/resolved.slang";

public void primeWavefrontResolve(
        uint2 launchIndex) {
    // Resolve owns reconstruction only; transport has already completed.
    uint2 pixel = launchIndex;
    if (primeWavefrontPixelTransparent(pixel)) {
        return;
    }
    PrimeWavefrontTransportRecord primaryRecord =
            primeWavefrontPaths[primeWavefrontIndex(pixel, 0u)].transport;
    PrimeOrdinarySample ordinary = primeLoadWavefrontIntermediate(
            pixel, primeWavefrontIndex(pixel, 0u), primaryRecord);
    PrimeResolvedSample result = primeResolveOrdinarySample(ordinary);
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    primeWriteRealtimeOutput(pixel, cameraSample, result);
}
