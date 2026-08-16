import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "core/camera.slang";
import "core/nrd_common.slang";
import "core/numerical.slang";
import "core/numerical_diagnostics.slang";
import "core/packed_geometry.slang";
import "core/render_config.slang";
import "integrator/integration_result.slang";
import "integrator/path_contract.slang";

#include "realtime/wavefront_storage.slang"
#include "realtime/wavefront_output.slang"

void primeWavefrontResolve(
        inout PrimeNumericalDiagnostics diagnostics, uint2 launchIndex) {
    // Resolve owns reconstruction only; transport has already completed.
    uint2 pixel = launchIndex;
    if (primeWavefrontPixelTransparent(pixel)) {
        return;
    }
    PrimeWavefrontTransportRecord primaryRecord =
            primeWavefrontPaths[primeWavefrontIndex(pixel, 0u)].transport;
    uint primaryDiagnostic =
            asuint(primaryRecord.throughputAndNumericalFlags.w);
    diagnostics.flags =
            primaryDiagnostic & PRIME_WAVEFRONT_NUMERICAL_FLAGS_MASK;
    diagnostics.firstContext =
            (primaryDiagnostic >> PRIME_WAVEFRONT_NUMERICAL_CONTEXT_SHIFT)
                    & PRIME_WAVEFRONT_NUMERICAL_CONTEXT_MASK;
    PrimeIntegrationResult result = primeLoadWavefrontIntermediate(
            pixel, primeWavefrontIndex(pixel, 0u), primaryRecord);
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    primeWriteRealtimeOutput(diagnostics, pixel, cameraSample, result);
}
